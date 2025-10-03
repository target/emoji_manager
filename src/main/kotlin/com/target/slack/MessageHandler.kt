package com.target.slack

import com.slack.api.bolt.context.builtin.ActionContext
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.bolt.context.builtin.ViewSubmissionContext
import com.slack.api.bolt.request.builtin.BlockActionRequest
import com.slack.api.bolt.request.builtin.ViewSubmissionRequest
import com.slack.api.bolt.util.JsonOps
import com.slack.api.bolt.util.Responder
import com.slack.api.methods.kotlin_extension.request.chat.blocks
import com.slack.api.methods.request.files.FilesCompleteUploadExternalRequest
import com.slack.api.methods.response.files.FilesCompleteUploadExternalResponse
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
            Pair("triggerId", req.payload.triggerId),
            Pair("channelId", req.payload.channel.id),
            Pair("messageTs", req.payload.message.ts)
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
            // Update the original message using the stored channel and timestamp from the button metadata
            val channelId = metadata["channelId"]
            val messageTs = metadata["messageTs"]

            if (channelId != null && messageTs != null) {
                val proposal = r.getOrNull()
                val updateResult = ctx.client().chatUpdate { req ->
                    req.channel(channelId)
                    req.ts(messageTs)
                    req.blocks {
                        section {
                            if (proposal?.permalink != null) {
                                markdownText("<${proposal.permalink}|Proposal posted here.>\nIf you wish to withdraw this proposal, react to that post with :${Proposals.WITHDRAW}: (`:${Proposals.WITHDRAW}:`).")
                            } else {
                                markdownText(":warning: Proposal failed to post. Please try again or contact an admin.")
                            }
                        }
                    }
                }
                logger.info("Chat update result: ${updateResult.isOk}, error: ${updateResult.error}")
            } else {
                logger.warn("Missing channelId or messageTs in metadata, cannot update original message")
            }
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
            // Modern Slack upload flow
            val uploadUrlResp = ctx.client().filesGetUploadURLExternal {
                it.filename(
                    if (ImageHelp.isGif(previewBytes)) {
                        "preview.$name.$rawSha1.gif"
                    } else {
                        "preview.$name.$rawSha1.png"
                    }
                )
                it.length(previewBytes?.size)
            }
            if (!uploadUrlResp.isOk || uploadUrlResp.uploadUrl == null) {
                logger.warn("Unable to get upload URL: ${uploadUrlResp.error}")
                warnings.add("Unable to get upload URL: ${uploadUrlResp.error}")
            } else {
                val uploadUrl = uploadUrlResp.uploadUrl
                val fileId = uploadUrlResp.fileId
                try {
                    val url = java.net.URL(uploadUrl)
                    val conn = url.openConnection() as java.net.HttpURLConnection
                    conn.requestMethod = "POST"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", if (ImageHelp.isGif(previewBytes)) "image/gif" else "image/png")
                    conn.setRequestProperty("Content-Length", previewBytes?.size.toString())
                    conn.outputStream.use {
                        if (previewBytes != null) {
                            it.write(previewBytes)
                        }
                    }
                    val responseCode = conn.responseCode
                    if (responseCode !in 200..299) {
                        logger.warn("Failed to upload file to Slack S3: HTTP $responseCode")
                        warnings.add("Failed to upload file to Slack S3: HTTP $responseCode")
                    } else {
                        val completeFileUploadResp: FilesCompleteUploadExternalResponse =
                            ctx.client().filesCompleteUploadExternal { req ->
                                req.files(
                                    listOf(
                                        FilesCompleteUploadExternalRequest.FileDetails.builder()
                                            .id(fileId) // <- from Step 1
                                            .title("$name preview")
                                            .build()
                                    )
                                )
                                req.channelId(ctx.channelId)
                                req.threadTs(eventTs)
                            }
                        if (!completeFileUploadResp.isOk || completeFileUploadResp.files.isNullOrEmpty()) {
                            logger.warn("Unable to complete upload: ${completeFileUploadResp.error}")
                            warnings.add("Unable to complete upload: ${completeFileUploadResp.error}")
                        } else {
                            // Wait for file to appear in thread
                            val maxTries = 10
                            val delayMs = 1000L
                            for (i in 1..maxTries) {
                                val replies = ctx.client().conversationsReplies {
                                    it.channel(ctx.channelId)
                                    it.ts(eventTs)
                                }
                                if (replies.isOk && replies.messages != null) {
                                    val found = replies.messages.any { m ->
                                        m.files?.any { f -> f.id == fileId } == true
                                    }
                                    if (found) {
                                        break
                                    }
                                }
                                Thread.sleep(delayMs)
                            }
                        }
                    }
                } catch (ex: Exception) {
                    logger.warn("Upload to Slack S3 failed: ${ex.message}")
                    warnings.add("Upload to Slack S3 failed: ${ex.message}")
                }
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
