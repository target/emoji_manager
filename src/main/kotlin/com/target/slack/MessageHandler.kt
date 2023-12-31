package com.target.slack

import com.slack.api.bolt.context.builtin.ActionContext
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.bolt.context.builtin.ViewSubmissionContext
import com.slack.api.bolt.request.builtin.BlockActionRequest
import com.slack.api.bolt.request.builtin.ViewSubmissionRequest
import com.slack.api.bolt.util.JsonOps
import com.slack.api.bolt.util.Responder
import com.slack.api.methods.kotlin_extension.request.chat.blocks
import com.slack.api.model.File
import com.slack.api.model.event.MessageFileShareEvent
import com.slack.api.model.kotlin_extension.block.withBlocks
import com.slack.api.model.kotlin_extension.view.blocks
import com.slack.api.model.view.Views.view
import com.slack.api.model.view.Views.viewClose
import com.slack.api.model.view.Views.viewSubmit
import com.slack.api.model.view.Views.viewTitle
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class MessageHandler(private val config: Config, private val emojiService: EmojiService) {

    companion object {
        const val PROPOSE_BUTTON = "emoji_propose_btn"
        const val SENDIT_FORM = "sendit_frm"
    }

    private var logger: Logger = LoggerFactory.getLogger(MessageHandler::class.java)

    fun handleMessage(event: MessageFileShareEvent, ctx: EventContext) {
        if (event.threadTs != null && event.ts != event.threadTs) {
            // ignore in-thread messages
            return
        }

        // Only respond to specific channels, DMs or Group DMs.
        if (event.channel in config.slack.slackProposalChannels ||
            event.channelType in arrayOf("im", "mpim")
        ) {
            if (event.files.size == 1) {
                return handleDmImagePost(event.ts, event.files[0], ctx)
            } else {
                ctx.client().chatPostMessage { p ->
                    p.channel(ctx.channelId)
                    p.text("If you want to propose a new emoji, you need to post a single image at a time.")
                    p.threadTs(event.ts)
                }
                logger.info("Got a proposal with more than one image.")
                return
            }
        } else {
            if (event.channel in config.slack.slackHintChannels) {
                val link = ctx.client().chatGetPermalink { l ->
                    l.channel(event.channel)
                    l.messageTs(event.ts)
                }
                if (link.isOk) {
                    ctx.client().chatPostMessage { p ->
                        p.channel(event.user)
                        p.unfurlLinks(true)
                        p.text("Hi there! I saw you posted an image to <${link.permalink}>.\nI no longer accept emoji proposals there. Instead, post your image here to propose a new emoji!")
                    }
                    ctx.client().chatPostEphemeral { p ->
                        p.channel(event.channel)
                        p.user(event.user)
                        p.text("Hi there! I no longer accept emoji proposals here. Instead, send me a DM of your image to propose a new emoji!")
                    }
                }
            }
            // ignore any extra channels we get invited to
            return
        }
    }

    fun handleProposeButtonClick(req: BlockActionRequest, ctx: ActionContext) {
        val metadata = mapOf(
            Pair("fileId", req.payload.actions[0].value),
            Pair("responseUrl", req.payload.responseUrl),
            Pair("triggerId", req.payload.triggerId)
        )

        val ogMsg = ctx.client().conversationsHistory {
            it.channel(req.payload.channel.id)
            it.oldest(req.payload.message.threadTs)
            it.latest(req.payload.message.threadTs)
            it.inclusive(true)
        }
        var defaultText = ""
        if (ogMsg.isOk) {
            for (msg in ogMsg.messages) {
                if (msg.ts == req.payload.message.threadTs) {
                    defaultText = msg.text
                }
            }
        }

        val view = view { thisView ->
            thisView.type("modal")
                .callbackId(SENDIT_FORM)
                .notifyOnClose(true)
                .title(viewTitle { it.type("plain_text").text("Emoji Proposal").emoji(true) })
                .submit(viewSubmit { it.type("plain_text").text("Submit").emoji(true) })
                .close(viewClose { it.type("plain_text").text("Cancel").emoji(true) })
                .privateMetadata(JsonOps.toJsonString(metadata))
                .blocks {
                    section {
                        markdownText("Please provide some context in the thread: What is this image? Where did it come from? How do you expect it to be used?")
                    }
                    input {
                        blockId("comment-block")
                        optional(true)
                        plainTextInput {
                            actionId("comment-action")
                            multiline(true)
                            initialValue(defaultText)
                        }
                        label("Say more...", emoji = true)
                    }
                }
        }

        val r = ctx.client().viewsOpen {
            it.triggerId(ctx.triggerId).view(view)
        }
        if (!r.isOk) {
            logger.warn(r.error)
        }
    }

    fun handleSenditFormSubmit(req: ViewSubmissionRequest, ctx: ViewSubmissionContext) {
        logger.info("got form submit")

        val mapType = mapOf(Pair("", ""), Pair("", ""))
        val metadata = try {
            JsonOps.fromJson(req.payload.view.privateMetadata, mapType.javaClass)
        } catch (ex: Exception) {
            logger.warn("Could not import metadata from form submission: ${ex.message}")
            return
        }
        val proposalFileInfo = try {
            ctx.client().filesInfo {
                it.file(metadata["fileId"])
            }
        } catch (ex: Exception) {
            logger.warn("Could not get file info: ${ex.message}")
            return
        }
        if (!proposalFileInfo.isOk) {
            logger.warn("Could not get file info: ${proposalFileInfo.error}")
            return
        }

        val eventFile = proposalFileInfo.file
        var comment = req.payload.view.state.values["comment-block"]?.get("comment-action")?.value?.replace("\n", "\n>")
        if (!comment.isNullOrEmpty() && comment.isNotBlank()) {
            comment = ">$comment"
        }
        val r = emojiService.generateProposal(ctx, req.payload.user.id, eventFile.id, comment)
        if (r.isFailure) {
            logger.warn(r.exceptionOrNull()?.message)
        } else {
            logger.info("Got new prop: ${r.getOrNull()}")
        }

        try {
            // update the original button

            val actionCtx = ActionContext()
            actionCtx.triggerId = metadata["triggerId"]
            actionCtx.responder = Responder(ctx.slack, metadata["responseUrl"])

            val u = actionCtx.respond {
                it.replaceOriginal(true)
                it.blocks(
                    withBlocks {
                        section {
                            markdownText("<${r.getOrNull()!!.permalink}|Proposal posted here.>\nIf you wish to withdraw this proposal, react to that post with :${Proposals.WITHDRAW}: (`:${Proposals.WITHDRAW}:`).")
                        }
                    }
                )
            }
            logger.info("Chat update: ${u.code}")
        } catch (ex: Exception) {
            logger.error("Error on update: ${ex.message}")
            logger.error(ex.stackTraceToString())
        }
    }

    private fun handleDmImagePost(eventTs: String, eventFile: File, ctx: EventContext) {
        val (name, extension) = stripExtension(eventFile.name)

        val emojiList = ctx.client().getAllEmoji()
        val isReplacement = (name in emojiList)

        // Download the file
        val rawFile = downloadFile(eventFile.urlPrivateDownload, ctx)
        val rawSha1 = hashString("SHA-1", rawFile)

        val warnings = ImageHelp.checkSize(rawFile)

        val result = ImageHelp.runCatching {
            generatePreview(rawFile)
        }

        if (result.isFailure) {
            logger.warn("Upload of preview.$name.$rawSha1.$extension failed:  ${result.exceptionOrNull()}")
            warnings.add("Image preview could not be generated: ${result.exceptionOrNull()?.message}")
        } else {
            val previewBytes = result.getOrNull()
            val r = ctx.client().filesUpload {
                it.channels(mutableListOf(ctx.channelId))
                it.threadTs(eventTs)
                it.title("$name preview")
                if (ImageHelp.isGif(previewBytes)) {
                    it.filename("preview.$name.$rawSha1.gif")
                } else {
                    it.filename("preview.$name.$rawSha1.png")
                }
                it.fileData(previewBytes)
            }
            if (!r.isOk) {
                logger.warn("Upload of preview.$name.$rawSha1 failed:  ${r.error}")
                warnings.add("Uploading the preview to Slack failed: ${r.error}")
            }
        }

        if (warnings.isNotEmpty()) {
            val warningMsg = "This image may not work well with Slack:\n• ${warnings.joinToString("\n• ")}"
            ctx.client().chatPostMessage {
                it.channel(ctx.channelId)
                it.text(warningMsg)
                it.threadTs(eventTs)
            }
        }

        val nameResult = isNameOk(name, eventFile.mimetype, emojiList)
        if (nameResult.isFailure) {
            ctx.client().chatPostMessage {
                it.channel(ctx.channelId)
                it.text(nameResult.exceptionOrNull()!!.message)
                it.threadTs(eventTs)
            }
        } else {
            val proposeText = if (isReplacement) {
                "Propose this image to replace current the current `:$name:` emoji :$name:?"
            } else {
                "Propose this image for the emoji `:$name:`?"
            }
            ctx.client().chatPostMessage {
                it.channel(ctx.channelId)
                it.text(proposeText)
                it.blocks {
                    section {
                        markdownText(proposeText)
                    }
                    actions {
                        button {
                            text("Propose It")
                            style("primary")
                            value(eventFile.id)
                            actionId(PROPOSE_BUTTON)
                        }
                    }
                }
                it.threadTs(eventTs)
            }
        }
        return
    }
}
