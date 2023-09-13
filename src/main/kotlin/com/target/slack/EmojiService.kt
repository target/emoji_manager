package com.target.slack

import com.slack.api.Slack
import com.slack.api.bolt.context.Context
import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.kotlin_extension.request.chat.blocks
import com.slack.api.methods.response.files.FilesUploadResponse
import com.slack.api.model.File
import com.target.liteforjdbc.Db
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.util.UUID

data class TallyResult(val up: Int = 0, val down: Int = 0, val block: Int = 0, val unblock: Int = 0, val userReport: Int = 0, val systemReport: Int = 0)

class EmojiService(private val config: Config, private val db: Db) {

    private val logger: Logger = LoggerFactory.getLogger(EmojiService::class.java)

    private var emojis = Emojis(db)
    private var emojiFiles = EmojiFiles(db)
    private var proposals = Proposals(db)
    private var auditLog = AuditLog(db)

    fun generateProposal(ctx: Context, requester: String, fileId: String, comment: String?): Result<Proposal> {
        val proposalFileInfo = ctx.client().filesInfo {
            it.file(fileId)
        }
        if (!proposalFileInfo.isOk) {
            logger.warn("Error getting file info: ${proposalFileInfo.error}")
            return Result.failure(Exception("Error getting file info: ${proposalFileInfo.error}"))
        }
        val rawFile = proposalFileInfo.file

        val (emojiname, extension) = stripExtension(rawFile.name)
        val rawFileBytes = downloadFile(rawFile.urlPrivateDownload, ctx)
        val rawSha1 = hashString("SHA-1", rawFileBytes)

        val warnings = ImageHelp.checkSize(rawFileBytes)

        val previewResult = ImageHelp.runCatching {
            generatePreview(rawFileBytes)
        }

        if (previewResult.isFailure) {
            return Result.failure(Exception(previewResult.exceptionOrNull()))
        }
        val previewBytes = previewResult.getOrNull()

        // When a user pastes an image from the clipboard, it's named `image.png` . To avoid too many
        // accidentally wrongly named emoji, lets just not support it.
        if (emojiname == "image") {
            logger.info("Got a proposal image named 'image'. Bailing.")
            return Result.failure(Exception("Sorry, for boring reasons we don't support managing the emoji `:image:`"))
        }

        // Make sure we can actually handle this file
        // Does "image/apng" work? What about "image/webp"?
        if (rawFile.mimetype !in arrayListOf("image/png", "image/jpg", "image/jpeg", "image/gif")) {
            logger.info("Got a proposal with image type ${rawFile.mimetype}, rejecting.")
            return Result.failure(Exception("That's a nice file you have, there.  Sadly, I don't know how to handle a file of type `${rawFile.mimetype}`. Try again with a jpg, png, or gif."))
        }
        val emojiList = ctx.client().getAllEmoji()
        val isReplacement = (emojiname in emojiList)

        if (isReplacement && (emojiList[emojiname].equals(BUILT_IN))) {
            logger.info("Got a proposal for $emojiname which is a built-in emoji.")
            return Result.failure(Exception("`:$emojiname:` is a built-in emoji, and there is no way to override that. Sorry, please choose another name."))
        }

        if (!slackNameRegex.matches(emojiname)) {
            logger.info("Got a proposal for $emojiname which has invalid characters.")
            return Result.failure(Exception("`:$emojiname:` contains characters that Slack wont allow. Slack only allows letters, numbers, underscore, hyphen, and plus, please choose another name."))
        }

        val replaceWords = if (isReplacement) "to replace :$emojiname:" else ""

        val r: FilesUploadResponse = try {
            ctx.client().filesUpload {
                it.channels(listOf(config.slack.slackEmojiChannel))
                it.title("$emojiname preview")
                if (ImageHelp.isGif(previewBytes)) {
                    it.filename("preview.$emojiname.$rawSha1.gif")
                } else {
                    it.filename("preview.$emojiname.$rawSha1.png")
                }
                it.fileData(previewBytes)
                it.initialComment(
                    if (comment.isNullOrBlank()) {
                        "<@$requester> has proposed a new emoji (`:$emojiname:`) $replaceWords!"
                    } else {
                        "<@$requester> has proposed a new emoji (`:$emojiname:`) $replaceWords!\n$comment"
                    }
                )
            }
        } catch (ex: Exception) {
            logger.warn("Whoa! ${ex.message}")
            logger.warn(ex.stackTraceToString())
            return Result.failure(ex)
        }
        if (!r.isOk) {
            return Result.failure(Exception("Unable to upload image post, ${r.error}"))
        }

        val previewFile = r.file
        val proposalTs = previewFile.shares.publicChannels[config.slack.slackEmojiChannel]?.get(0)?.ts
            ?: return Result.failure(Exception("Unable to determine proposal timestamp"))

        val proposalLink = ctx.client().chatGetPermalink {
            it.channel(config.slack.slackEmojiChannel)
            it.messageTs(proposalTs)
        }.permalink

        val prop = db.withTransaction {
            val propFile = emojiFiles.upsert(
                EmojiFile(
                    sha1 = rawSha1,
                    file = rawFileBytes,
                    contentType = rawFile.mimetype,
                )
            )
            val prop: Proposal = proposals.upsert(
                Proposal(
                    emoji = emojiname,
                    thread = proposalTs,
                    permalink = proposalLink,
                    fileId = propFile.sha1,
                    user = requester,
                )
            )

            auditLog.insert(
                AuditEntry(
                    actorId = requester,
                    action = AuditLog.ACTION_PROPOSE_NEW,
                    emoji = prop.emoji,
                    proposalId = prop.id,
                )
            )

            logger.info("$requester proposed a new emoji $emojiname: ${prop.id}")
            return@withTransaction prop
        }

        savePreview(previewFile, prop)

        ctx.client().reactionsAdd {
            it.channel(config.slack.slackEmojiChannel)
            it.timestamp(proposalTs)
            it.name(Proposals.UPVOTE)
        }
        ctx.client().reactionsAdd {
            it.channel(config.slack.slackEmojiChannel)
            it.timestamp(proposalTs)
            it.name(Proposals.DOWNVOTE)
        }

        ctx.client().chatPostMessage {
            it.channel(config.slack.slackEmojiChannel)
            it.threadTs(proposalTs)
            it.text(buildProposalVotingRules("emoji will be added"))
        }

        val r2 = ctx.client().filesUpload {
            it.channels(listOf(config.slack.slackEmojiChannel))
            it.threadTs(proposalTs)
            it.title(emojiname)
            it.filename(rawFile.name)
            it.fileData(rawFileBytes)
            it.initialComment("Original image:")
        }
        if (!r2.isOk) {
            return Result.failure(Exception("Unable to upload image post, ${r.error}"))
        }

        if (warnings.isNotEmpty()) {
            val warningMsg = "This image may not work well with Slack:\n• ${warnings.joinToString("\n• ")}"
            ctx.client().chatPostMessage {
                it.channel(config.slack.slackEmojiChannel)
                it.text(warningMsg)
                it.threadTs(proposalTs)
            }
        }
        return Result.success(prop)
    }

    fun handleAliasFunction(ctx: Context, requester: String, emojiname: String, aliasname: String): Result<Proposal> {
        val emojiList = ctx.client().getAllEmoji()

        if (!slackNameRegex.matches(aliasname)) {
            logger.info("Got a proposal for $aliasname which has invalid characters.")
            return Result.failure(Exception("`:$aliasname:` contains characters that Slack wont allow. Slack only allows letters, numbers, underscore, hyphen, and plus, please choose another name."))
        }

        // First make sure emojiname is a live emoji, or built-in
        if (emojiname !in emojiList) {
            logger.info("$requester requested an alias for $emojiname but it isn't an emoji.")
            return Result.failure(Exception("You can't make an alias for an emoji that does not exist yet.  Please, try again."))
        }

        // post about it so we have a thread timestamp
        val resp = ctx.client().chatPostMessage {
            it.channel(config.slack.slackEmojiChannel)
            it.text("New Alias Proposal: `:$aliasname:` for :$emojiname:(`:$emojiname:`) by <@$requester>")
        }
        if (!resp.isOk) {
            logger.warn("Tried to post a message, but failed: ${resp.error}")
            throw Exception("Tried to post a message, but failed: ${resp.error}")
        }
        val ts = resp.ts

        postProposal(
            ctx,
            config.slack.slackEmojiChannel,
            ts,
            buildProposalVotingRules("alias will be added")
        )

        val link = ctx.client().chatGetPermalink {
            it.channel(config.slack.slackEmojiChannel)
            it.messageTs(ts)
        }

        if (!link.isOk) {
            logger.warn("Unable to get permalink to conversation: ${link.error}")
            return Result.failure(Exception("Unable to obtain a link to the conversation. Please try again or contact an administrator for assistance."))
        }

        val prop = proposals.upsert(
            Proposal(
                alias = true,
                emoji = aliasname,
                cname = emojiname,
                user = requester,
                thread = ts,
                permalink = link.permalink,
            )
        )

        auditLog.insert(
            AuditEntry(
                actorId = requester,
                action = AuditLog.ACTION_PROPOSE_NEW,
                proposalId = prop.id,
                emoji = emojiname,
            )
        )

        logger.info("$requester proposed an alias of $aliasname for $emojiname: ${prop.id}")
        return Result.success(prop)
    }

    fun handleRemoveFunction(ctx: Context, requester: String, emojiname: String): Result<Proposal> {
        val emojiList = ctx.client().getAllEmoji()

        // Can we remove it?
        if (emojiname in emojiList && emojiList[emojiname] == BUILT_IN) {
            logger.info("$requester tried to remove a built-in emoji $emojiname. Bailing.")
            return Result.failure(Exception("`:$emojiname:` is a built-in emoji, you can't delete it. Sorry about that."))
        }
        // Make sure emojiname is a live emoji
        if (emojiname !in emojiList) {
            logger.info("$requester tried to remove $emojiname, which is not an emoji. Bailing.")
            return Result.failure(Exception("You can't delete an emoji that does not exist yet.  Please, try again."))
        }

        // post about it so we have a thread timestamp
        val isAlias = (emojiList.getValue(emojiname).startsWith(ALIAS))
        var msg = "Emoji Deletion Proposal: :$emojiname:(`:$emojiname:`) by <@$requester>"
        if (isAlias) {
            msg =
                "Alias Deletion Proposal: Remove the alias `:$emojiname:` :$emojiname: for `:${emojiList[emojiname]}:` by <@$requester>"
        }
        val resp = ctx.client().chatPostMessage {
            it.channel(config.slack.slackEmojiChannel)
            it.text(msg)
        }
        if (!resp.isOk) {
            return Result.failure(Exception("Tried to post a message, but it failed: ${resp.error}"))
        }
        val ts = resp.ts

        postProposal(
            ctx,
            config.slack.slackEmojiChannel,
            ts,
            buildProposalVotingRules("${if (isAlias) "alias" else "emoji"} will be deleted")
        )

        val link = ctx.client().chatGetPermalink {
            it.channel(config.slack.slackEmojiChannel)
            it.messageTs(ts)
        }

        if (!link.isOk) {
            logger.warn("Unable to get permalink to conversation: ${link.error}")
            return Result.failure(Exception("Unable to obtain a link to the conversation. Please try again or contact an administrator for assistance."))
        }

        val prop = proposals.upsert(
            Proposal(
                emoji = emojiname,
                action = Proposals.ACTION_REMOVE,
                user = requester,
                thread = ts,
                permalink = link.permalink,
            )
        )

        auditLog.insert(
            AuditEntry(
                actorId = requester,
                action = AuditLog.ACTION_PROPOSE_DEL,
                proposalId = prop.id,
                emoji = emojiname,
            )
        )

        logger.info("$requester proposed to delete $emojiname: ${prop.id}")
        return Result.success(prop)
    }

    fun recordVote(ctx: Context, ts: String, user: String, action: String): Boolean {
        var valid = false
        db.withTransaction {
            // Find the proposal
            val prop = proposals.findByThread(ts)
            if (prop != null) {
                logger.warn("$user $action on proposal ${prop.id}")
                auditLog.insert(
                    AuditEntry(
                        actorId = user,
                        action = action,
                        proposalId = prop.id,
                    )
                )
                valid = true

                val tallyResult = tallyVotes(prop)
                // Should we report this to the admins?
                if (tallyResult.systemReport == 0 && tallyResult.down >= config.votes.downVoteThreshold) {
                    logger.info("Proposal ${prop.id} has ${tallyResult.down} downvotes. Attempting to flag.")
                    val r = ctx.client().reactionsAdd() {
                        it.channel(config.slack.slackEmojiChannel)
                        it.timestamp(ts)
                        it.name(Proposals.REPORT)
                    }

                    if (r.isOk) {
                        recordVote(ctx, ts, user, AuditLog.ACTION_SYSTEM_REPORT)

                        if (config.slack.slackEmojiAdminChannel.isNotEmpty()) {
                            logger.warn("${prop.id} has ${config.votes.downVoteThreshold} downvotes. Notifying admins.")

                            var body = when (prop.action) {
                                Proposals.ACTION_ADD ->
                                    ":heavy_plus_sign:`:${prop.emoji}:` "
                                Proposals.ACTION_REMOVE ->
                                    ":heavy_minus_sign:`:${prop.emoji}:` "
                                else ->
                                    ""
                            }

                            if (prop.alias) body += "(alias for `:${prop.cname}:` :${prop.cname}:) "

                            body += "<${prop.permalink}|Thread> "
                            if (tallyResult.block - tallyResult.unblock > 0) body += ":${Proposals.BLOCK}: "

                            body += "${tallyResult.up}:${Proposals.UPVOTE}: ${tallyResult.down}:${Proposals.DOWNVOTE}: net: ${tallyResult.up - tallyResult.down}"
                            if (tallyResult.up - tallyResult.down > config.votes.winBy) body += ":+1: "

                            ctx.client().chatPostMessage { p ->
                                p.channel(config.slack.slackEmojiAdminChannel)
                                p.unfurlLinks(true)
                                p.unfurlMedia(true)
                                p.text("Auto reporting $body")
                                p.blocks {
                                    section {
                                        markdownText("Auto reporting $body")
                                    }
                                }
                            }
                        }
                    } else { // reaction failed
                        if (r.error != "already_reacted") {
                            logger.error("Unable to flag proposal ${prop.id}: ${r.error}")
                        }
                    }
                    logger.info(r.error)
                }
            }
        }
        return valid
    }

    fun withdrawProposal(ctx: Context, ts: String) {
        db.withTransaction {
            val prop = proposals.findByThread(ts)
            if (prop != null) {
                prop.state = Proposals.STATE_WITHDRAWN
                proposals.upsert(prop)
                recordVote(ctx, ts, prop.user, AuditLog.ACTION_SYSTEM_WITHDRAW)
            }
        }
    }

    fun createAlias(adminClient: AsyncMethodsClient, prop: Proposal): ActionResult {
        var success = false
        var error: String? = null
        if (!prop.alias || prop.cname == null) {
            logger.warn("Somehow we were told to make an alias for proposal ${prop.id} but it is incomplete.")
            return ActionResult(false, "Cannot make an alias without a cname")
        }
        db.withTransaction {
            removeSpecificEmoji(adminClient, prop.emoji, prop)
            val emoji = emojis.upsert(
                Emoji(
                    name = prop.emoji,
                    proposalId = prop.id,
                    alias = prop.alias,
                    cname = prop.cname,
                )
            )

            prop.state = Proposals.STATE_ACCEPTED
            proposals.upsert(prop)

            val audit = AuditEntry(
                emoji = emoji.name,
                proposalId = prop.id,
                actorId = AuditLog.ACTOR_ID_SYSTEM,
                action = AuditLog.ACTION_SYSTEM_UPLOAD,
            )

            val r = adminClient.adminEmojiAddAlias {
                it.name(prop.emoji)
                it.aliasFor(prop.cname)
            }.get()

            if (r.isOk) {
                success = true
                logger.info("Successfully added ${prop.emoji} alias to Slack. Proposal: ${prop.id}")
            } else {
                logger.error("Failed to add alias to Slack. Proposal: ${prop.emoji} ${prop.id} error: ${r.error} ")
                prop.state = Proposals.STATE_FAILED
                proposals.upsert(prop)
                audit.action = AuditLog.ACTION_SYSTEM_FAIL
                audit.note = "Failed to create alias. Slack response: ${r.error}"
                error = r.error
            }
            auditLog.insert(audit)
        }
        return ActionResult(success, error)
    }

    fun uploadEmoji(adminClient: AsyncMethodsClient, prop: Proposal): ActionResult {
        var success = false
        var error: String? = null

        if (prop.fileId == null) {
            logger.error("proposal ${prop.id} has no file, but were told to upload the emoji. Bailing.")
            return ActionResult(false, "Proposal has no file to upload")
        }
        db.withTransaction {
            removeSpecificEmoji(adminClient, prop.emoji, prop)
            val emoji = emojis.upsert(
                Emoji(
                    name = prop.emoji,
                    proposalId = prop.id,
                    file = prop.fileId,
                    alias = prop.alias,
                    cname = prop.cname,
                )
            )

            prop.state = Proposals.STATE_ACCEPTED
            proposals.upsert(prop)

            val audit = AuditEntry(
                emoji = emoji.name,
                proposalId = prop.id,
                actorId = AuditLog.ACTOR_ID_SYSTEM,
                action = AuditLog.ACTION_SYSTEM_UPLOAD,
            )

            logger.warn("Attempting to upload emoji: ${prop.emoji} using url ${config.server.urlPrefix}${config.server.urlPath}_images_${prop.fileId!!}")

            val r = adminClient.adminEmojiAdd {
                it.name(prop.emoji)
                it.url("${config.server.urlPrefix}${config.server.urlPath}_images_${prop.fileId!!}")
            }.get()

            if (r.isOk) {
                success = true
                logger.info("Successfully added ${prop.emoji} to Slack. Proposal: ${prop.id}")
            } else {
                logger.error("proposal ${prop.id} upload filed: ${r.error}")
                prop.state = Proposals.STATE_FAILED
                proposals.upsert(prop)
                audit.action = AuditLog.ACTION_SYSTEM_FAIL
                audit.note = "Failed to upload emoji. Slack response: ${r.error}"

                error = r.error
            }
            auditLog.insert(audit)
        }
        return ActionResult(success, error)
    }

    fun removeEmoji(adminClient: AsyncMethodsClient, prop: Proposal): ActionResult {
        val r = removeSpecificEmoji(adminClient, prop.emoji, prop)
        if (r.ok) {
            prop.state = Proposals.STATE_ACCEPTED
        } else {
            prop.state = Proposals.STATE_FAILED
        }
        proposals.upsert(prop)
        return r
    }

    /* This function removes the named emoji from Slack, recording it in the
       audit log in association with the given proposal, even when the proposed
       emoji is not the one we are deleting.  This is used when updating an
       existing emoji, as well as when deleting an emoji directly.
     */
    private fun removeSpecificEmoji(adminClient: AsyncMethodsClient, name: String, prop: Proposal): ActionResult {
        var success = false
        var error: String? = null

        val emoji = emojis.findEmojiByName(name)
        if (emoji != null) {
            emojis.removeEmojiByName(name)
        }
        val audit = AuditEntry(
            emoji = emoji?.name,
            proposalId = prop.id,
            actorId = AuditLog.ACTOR_ID_SYSTEM,
            action = AuditLog.ACTION_SYSTEM_DELETE,

        )

        val r = adminClient.adminEmojiRemove {
            it.name(name)
        }.get()

        if (r.isOk) {
            success = true
            logger.info("Successfully removed $name from Slack. Proposal: ${prop.id}")
        } else {
            // Trying to delete a thing that dosnt exist isnt an error worth logging
            if (r.error != "emoji_not_found") {
                logger.error("Failed to remove $name from Slack. Proposal: ${prop.id} Error: ${r.error}")
                audit.action = AuditLog.ACTION_SYSTEM_FAIL
                audit.note = "Failed to remove emoji. Slack response: ${r.error}"
                error = r.error
            }
        }

        auditLog.insert(audit)
        return ActionResult(success, error)
    }

    private fun performAction(adminClient: AsyncMethodsClient, prop: Proposal): ActionResult {
        if (prop.action == Proposals.ACTION_ADD) {
            if (prop.alias) {
                return createAlias(adminClient, prop)
            }
            return uploadEmoji(adminClient, prop)
        }
        if (prop.action == Proposals.ACTION_REMOVE) {
            return removeEmoji(adminClient, prop)
        }
        logger.warn("Unknown action in proposal ${prop.id} (${prop.action})")
        return ActionResult(false, "Unknown action in proposal")
    }

    private fun savePreview(file: File, prop: Proposal) {
        prop.previewFile = file.id
        prop.previewUrl = file.thumb64
        proposals.upsert(prop)
    }

    /* Tally the votes for all proposals, and take action if there are enough votes.
     * Accepts a time for when "now" is, but that is intended for testing purposes only
     */
    fun tallyVotes(now: LocalDateTime = LocalDateTime.now()) {
        val client = Slack.getInstance().methods(config.slack.slackBotToken.value)
        val adminClient = Slack.getInstance().methodsAsync(config.slack.slackAdminToken.value)
        logger.warn("Starting tally...")

        val newProposals = proposals.findAllByState(Proposals.STATE_NEW)

        for (prop in newProposals) {
            logger.warn("${prop.id} tallying votes (started on ${prop.created})")

            if (prop.created.plusBusinessDays(config.votes.commentPeriod, config.votes.calendarHolidays).isAfter(now)) {
                // too soon to tally
                logger.info("${prop.id} is still in the comment period")
                continue
            }
            val tallyResult = tallyVotes(prop)

            if (tallyResult.block - tallyResult.unblock > 0) {
                logger.info("${prop.id} is blocked and will not tally.")
            } else {
                logger.warn("${prop.id} has ${tallyResult.up} upvotes and ${tallyResult.down} downvotes. Net: ${tallyResult.up - tallyResult.down} WinBy: ${config.votes.winBy}")
                if (tallyResult.up - tallyResult.down > config.votes.winBy) {
                    // We have enough votes to pass
                    val action = if (prop.action == Proposals.ACTION_ADD) "added" else "removed"
                    val emoji = if (prop.action == Proposals.ACTION_ADD) ":${prop.emoji}:" else "`:${prop.emoji}:`"

                    client.chatPostMessage { p ->
                        p.channel(config.slack.slackEmojiChannel)
                        p.text("The community has spoken; this emoji will be $action shortly. :tada:")
                        p.threadTs(prop.thread)
                    }

                    val a = performAction(adminClient, prop)
                    if (a.ok) {
                        client.chatPostMessage { p ->
                            p.channel(config.slack.slackEmojiChannel)
                            p.text("$emoji has been $action.")
                            p.threadTs(prop.thread)
                            p.replyBroadcast(true)
                        }
                    } else {
                        client.chatPostMessage { p ->
                            p.channel(config.slack.slackEmojiChannel)
                            p.text("Oh no! Something went wrong. Please have an admin. look into this.\nProposal id ${prop.id}\nError: `${a.error}`")
                            p.threadTs(prop.thread)
                        }
                    }
                    continue
                }
            }

            if (prop.created.plusBusinessDays(config.votes.maxDuration, config.votes.calendarHolidays).isBefore(now)) {
                // Voting period ran out
                logger.warn("${prop.id} passed maxDuration without enough votes. Closing")
                client.chatPostMessage { p ->
                    p.channel(config.slack.slackEmojiChannel)
                    p.text("Voting is now closed.")
                    p.threadTs(prop.thread)
                }

                prop.state = Proposals.STATE_REJECTED
                proposals.upsert(prop)
                auditLog.insert(
                    AuditEntry(
                        emoji = prop.emoji,
                        proposalId = prop.id,
                        actorId = AuditLog.ACTOR_ID_SYSTEM,
                        action = AuditLog.ACTION_SYSTEM_REJECT,
                    )
                )
                continue
            }
        }
    }

    /* Tally the votes for a single proposal, and return the results (take no action)
     *
     */
    fun tallyVotes(prop: Proposal): TallyResult {
        var tallyResult = TallyResult()

        val auditEntires = auditLog.findByProposal(prop.id)
        val proposal = auditEntires.filter { it.action == AuditLog.ACTION_PROPOSE_NEW || it.action == AuditLog.ACTION_PROPOSE_DEL }
        val upVotes = auditEntires.filter { it.action == AuditLog.ACTION_VOTE_UP }
        val downVotes = auditEntires.filter { it.action == AuditLog.ACTION_VOTE_DN }
        val blockVotes = auditEntires.filter { it.action == AuditLog.ACTION_ADMIN_BLOCK }
        val unblockVotes = auditEntires.filter { it.action == AuditLog.ACTION_ADMIN_UNBLOCK }
        val userReports = auditEntires.filter { it.action == AuditLog.ACTION_USER_REPORT }
        val systemReports = auditEntires.filter { it.action == AuditLog.ACTION_SYSTEM_REPORT }

        if (proposal.size != 1) {
            logger.warn("Proposal ${prop.id} has no 'new' AuditEntry associated with it.  Bailing.")
            return tallyResult
        }

        tallyResult = TallyResult(upVotes.size, downVotes.size, blockVotes.size, unblockVotes.size, userReports.size, systemReports.size)

        return tallyResult
    }

    /*
    Returns a string of the voting rules, based on current rules Config and Proposal up/downvotes

    Usage example: buildProposalVotingRules("this bot will self-destruct")
     */
    private fun buildProposalVotingRules(outcomeIfApproved: String): String {
        return "Vote with :${Proposals.UPVOTE}: or :${Proposals.DOWNVOTE}:\n" +
            "After ${config.votes.commentPeriod} business day${if (config.votes.commentPeriod > 1) "s" else ""}, " +
            "if there are at least ${config.votes.winBy + 1} more " +
            ":${Proposals.UPVOTE}: than :${Proposals.DOWNVOTE}: votes, the $outcomeIfApproved. " +
            "Voting will close after ${config.votes.maxDuration} business days otherwise.\n\n" +
            "Please provide some context in the thread: " +
            "What is this image? Where did it come from? How do you expect it to be used?"
    }

    private fun postProposal(ctx: Context, channel: String, ts: String, message: String) {
        ctx.client().chatPostMessage {
            it.channel(channel)
            it.text(message)
            it.threadTs(ts)
        }
        ctx.client().reactionsAdd {
            it.channel(channel)
            it.timestamp(ts)
            it.name(Proposals.UPVOTE)
        }
        ctx.client().reactionsAdd {
            it.channel(channel)
            it.timestamp(ts)
            it.name(Proposals.DOWNVOTE)
        }
    }

    fun adminUpdateProposal(proposal: Proposal, actor: String): Proposal {
        return db.withTransaction {
            auditLog.insert(
                AuditEntry(
                    actorId = actor,
                    action = AuditLog.ACTION_ADMIN_RESET,
                    proposalId = proposal.id,
                    emoji = proposal.emoji
                )
            )
            return@withTransaction proposals.upsert(proposal)
        }
    }

    fun findEmojiByName(name: String): Emoji? {
        return emojis.findEmojiByName(name)
    }

    fun findProposalByThread(timestamp: String): Proposal? {
        return proposals.findByThread(timestamp)
    }

    fun findProposalById(id: UUID): Proposal? {
        return proposals.findProposalByID(id)
    }

    fun findProposalsByState(state: String, limit: Int? = null): List<Proposal> {
        return proposals.findAllByState(state, limit)
    }

    fun getRecentlyAdded(days: Long = 14, limit: Int = 32): List<Pair<Proposal, LocalDateTime>> {
        return proposals.getRecentlyAdded(days, limit)
    }

    fun getRecentErrors(days: Long = 14, limit: Int = 32): List<Pair<Proposal, AuditEntry>> {
        return proposals.getRecentErrors(days, limit)
    }

    fun userIsAdmin(user: String): Boolean {
        return (user in config.slack.slackAdminUsers)
    }
}
