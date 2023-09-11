package com.target.slack

import com.slack.api.bolt.context.builtin.SlashCommandContext
import com.slack.api.bolt.request.builtin.SlashCommandRequest
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

class SlashCommandHandler(private val config: Config, private val emojiService: EmojiService) {

    private var logger: Logger = LoggerFactory.getLogger(SlashCommandHandler::class.java)

    fun handleCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        return req.payload.text.split("\\s+".toRegex()).let { tokens ->
            when (tokens.getOrNull(0)) {
                "version" -> handleVersionCommand(req, ctx)
                "status" -> handleStatusCommand(req, ctx)
                "alias" -> handleAliasCommand(req, ctx)
                "remove" -> handleRemoveCommand(req, ctx)
                "delete" -> handleRemoveCommand(req, ctx)
                "reset" -> handleResetCommand(req, ctx)
                "tally" -> handleTallyCommand(req, ctx)
                "fakevote" -> handleFakeVoteCommand(req, ctx)
                else -> handleHelpCommand(req, ctx).also { logger.warn("Unregistered slash command. Sending help.") }
            }
        }
    }

    private fun handleHelpCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        var helpMessage = "${config.text.intro}\n\n${config.text.help.general}\n"

        // only show admin commands to admins
        if (emojiService.userIsAdmin(req.payload.userId)) {
            helpMessage += "*Admin Only Features*\n${config.text.help.admin}"
        }

        ctx.respond { it.text(helpMessage) }
    }

    private fun handleVersionCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        ctx.respond { r ->
            r.responseType("ephemeral")
            r.text(getVersionNumber())
        }
        logger.info("${req.payload.userId} requested version number")
        return
    }
    private fun handleStatusCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        val tokens = req.payload.text.split(" ")

        if (tokens.size == 1) {
            val newProposals = emojiService.findProposalsByState(Proposals.STATE_NEW).sortedBy { it.created }

            var body = "Currently open proposals:"
            for (prop in newProposals) {
                body += "\n`:${prop.emoji}:` ${formatStatusLine(prop, emojiService.userIsAdmin(req.payload.userId))}"
            }

            ctx.respond { r ->
                r.responseType("ephemeral")
                r.text(body)
            }
            logger.info("${req.payload.userId} requested status of all new proposals")
            return
        }
        if (tokens.size != 2) {
            ctx.respond { it.text(config.text.help.cmdStatus) }
            return
        }

        try {
            val uuid = UUID.fromString(tokens[1])

            val prop = emojiService.findProposalById(uuid)
            if (prop == null) {
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text("Can't find a proposal with that ID")
                }
                logger.info("${req.payload.userId} requested status of unknown ID")
            } else {
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text("`${prop.state}` `:${prop.emoji}:` ${formatStatusLine(prop, emojiService.userIsAdmin(req.payload.userId))}")
                }
                logger.info("${req.payload.userId} requested status of ${tokens[1]}")
            }

            return
        } catch (e: IllegalArgumentException) {
            // Not a UUID, must be a name, continue on
        }
        val name = stripColons(tokens[1])

        val emoji: Emoji? = emojiService.findEmojiByName(name)
        val newProposals: List<Proposal> = emojiService.findProposalsByState(Proposals.STATE_NEW)
            .filter { it.emoji == name }
            .sortedBy { it.created }
        val acceptedProposals: List<Proposal> = emojiService.findProposalsByState(Proposals.STATE_ACCEPTED)
            .filter { it.emoji == name }
            .sortedBy { it.created }
        val rejectedProposals: List<Proposal> = emojiService.findProposalsByState(Proposals.STATE_REJECTED)
            .filter { it.emoji == name }
            .sortedBy { it.created }

        val emojiList = ctx.client().getAllEmoji()

        var body: String = if (name in emojiList) {
            if (emojiList.getValue(name).startsWith(ALIAS)) {
                ":$name: `:$name:` is currently an `${emojiList[name]}`" // Note: The value is "alias:cname"
            } else if (emojiList[name] == BUILT_IN) {
                ":$name: `:$name:` is a built-in emoji"
            } else {
                ":$name: `:$name:` is live"
            }
        } else {
            "`:$name:` is not live yet"
        }

        if (emoji != null) {
            val prop = emojiService.findProposalById(emoji.proposalId)
            if (prop != null) {
                val t = emoji.updated.toEpochSecond(ZoneOffset.UTC)
                body += "\nIt was last changed by <@${prop.user}> on <!date^$t^{date} {time}|${emoji.updated}>"
            }
        }

        newProposals.forEach { prop ->
            body += "\nNew ${formatStatusLine(prop, emojiService.userIsAdmin(req.payload.userId))}"
        }
        acceptedProposals.forEach { prop ->
            body += "\nAccepted ${formatStatusLine(prop, emojiService.userIsAdmin(req.payload.userId))}"
        }
        for (prop in rejectedProposals) {
            body += "\nRejected ${formatStatusLine(prop, emojiService.userIsAdmin(req.payload.userId))}"
        }

        ctx.respond { r ->
            r.responseType("ephemeral")
            r.text(body)
        }
        logger.info("${req.payload.userId} requested status of $name")

        return
    }

    private fun handleAliasCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        val tokens = req.payload.text.split(" ")

        if (tokens.size != 3) {
            ctx.respond { it.text(config.text.help.cmdAlias) }
            return
        }

        val emojiname = stripColons(tokens[1])
        val aliasname = stripColons(tokens[2]).lowercase()
        val requester = req.payload.userId

        emojiService.handleAliasFunction(ctx, requester, emojiname, aliasname)
            .onFailure { ex ->
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text(ex.message)
                }
            }
    }

    private fun handleRemoveCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        val tokens = req.payload.text.split(" ")
        if (tokens.size != 2) {
            ctx.respond { it.text(config.text.help.cmdRemove) }
            return
        }

        val emojiname = stripColons(tokens[1])

        emojiService.handleRemoveFunction(ctx, req.payload.userId, emojiname)
            .onFailure { ex ->
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text(ex.message)
                }
            }
    }

    private fun stripColons(token: String): String {
        val newToken = if (token.startsWith(":")) token.drop(1).dropLast(1) else token
        return newToken
    }

    private fun handleResetCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        if (!emojiService.userIsAdmin(req.payload.userId)) {
            // This is a hidden, admin-only command.
            return handleHelpCommand(req, ctx)
        }

        val tokens = req.payload.text.split(" ")
        if (!(tokens.size == 2 || tokens.size == 3)) {
            ctx.respond { it.text(config.text.help.cmdReset) }
            return
        }

        val propId = tokens[1]
        val state = when (tokens.getOrElse(2) { "new" }) {
            "new" -> Proposals.STATE_NEW
            "failed" -> Proposals.STATE_FAILED
            "accepted" -> Proposals.STATE_ACCEPTED
            "rejected" -> Proposals.STATE_REJECTED
            "withdrawn" -> Proposals.STATE_WITHDRAWN
            else -> {
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text("Must reset to `new`, `failed`, `accepted`, `rejected`, or `withdrawn`")
                }
                return
            }
        }

        val prop: Proposal? = try {
            emojiService.findProposalById(UUID.fromString(propId))
        } catch (e: IllegalArgumentException) {
            emojiService.findProposalByThread(propId)
        }

        if (prop == null) {
            ctx.respond { r ->
                r.responseType("ephemeral")
                r.text("Proposal not found.")
            }
        } else {
            val origState = prop.state
            prop.state = state
            emojiService.adminUpdateProposal(prop, req.payload.userId)

            ctx.respond { r ->
                r.responseType("ephemeral")
                r.text("<${prop.permalink}|Proposal> ${prop.id} state changed from $origState to $state")
            }
            logger.warn("Admin ${req.payload.userId} reset status of ${prop.id} from $origState to $state")
        }

        return
    }

    private fun handleTallyCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        if (!emojiService.userIsAdmin(req.payload.userId)) {
            // This is a hidden, admin-only command.
            return handleHelpCommand(req, ctx)
        }

        val tokens = req.payload.text.split(" ")
        if (tokens.size != 1) {
            ctx.respond { it.text(config.text.help.cmdTally) }
            return
        }

        logger.warn("Admin ${req.payload.userId} triggered a vote tally now")
        emojiService.tallyVotes()
    }

    private fun handleFakeVoteCommand(req: SlashCommandRequest, ctx: SlashCommandContext) {
        if (!emojiService.userIsAdmin(req.payload.userId)) {
            // This is a hidden, admin-only command.
            return handleHelpCommand(req, ctx)
        }

        val tokens = req.payload.text.split(" ")
        if (tokens.size != 3) {
            ctx.respond { it.text(config.text.help.cmdFakevote) }
            return
        }

        val action: String
        val propId = tokens[1]
        val vote = tokens[2]

        action = when (vote) {
            "up" -> AuditLog.ACTION_VOTE_UP
            "down" -> AuditLog.ACTION_VOTE_DN
            "block" -> AuditLog.ACTION_ADMIN_BLOCK
            "unblock" -> AuditLog.ACTION_ADMIN_UNBLOCK
            else -> {
                ctx.respond { r ->
                    r.responseType("ephemeral")
                    r.text("Must vote `up`, `down`, `block`, or `unblock`")
                }
                return
            }
        }

        val prop: Proposal? = try {
            emojiService.findProposalById(UUID.fromString(propId))
        } catch (e: IllegalArgumentException) {
            emojiService.findProposalByThread(propId)
        }
        if (prop == null) {
            ctx.respond { r ->
                r.responseType("ephemeral")
                r.text("Proposal not found.")
            }
        } else {
            emojiService.recordVote(ctx, prop.thread, req.payload.userId, action)

            ctx.respond { r ->
                r.responseType("ephemeral")
                r.text("Added $vote vote for <${prop.permalink}|proposal> ${prop.id}")
            }
            logger.warn("Admin ${req.payload.userId} registered a fake vote ($action) for ${prop.id}")
        }
    }

    private fun formatStatusLine(prop: Proposal, isAdmin: Boolean = false): String {
        val inCommentPeriod = prop.created.plusBusinessDays(config.votes.commentPeriod).isAfter(LocalDateTime.now())
        val propId = if (isAdmin) " ${prop.id}" else ""
        val tallyResult = emojiService.tallyVotes(prop)

        var body = ""

        if (prop.alias) body += "(alias)"
        body += "<${prop.permalink}|Proposal> "
        if (tallyResult.block - tallyResult.unblock > 0) {
            body += ":${Proposals.BLOCK}: "
        }
        body += "by <@${prop.user}> on "
        body += "<!date^${prop.created.toEpochSecond(ZoneOffset.UTC)}^{date} {time}|${prop.created}> "
        if (inCommentPeriod) body += ":speech_balloon: "
        body += "${tallyResult.up}:${Proposals.UPVOTE}: ${tallyResult.down}:${Proposals.DOWNVOTE}: "
        if (tallyResult.up - tallyResult.down > config.votes.winBy) body += ":+1: "
        body += propId

        return body
    }
}
