package com.target.slack

import com.slack.api.bolt.context.builtin.ActionContext
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.bolt.request.builtin.BlockActionRequest
import com.slack.api.model.event.AppHomeOpenedEvent
import com.slack.api.model.kotlin_extension.block.dsl.LayoutBlockDsl
import com.slack.api.model.kotlin_extension.block.element.ButtonStyle
import com.slack.api.model.kotlin_extension.view.blocks
import com.slack.api.model.view.View
import com.slack.api.model.view.Views.view
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.ZoneOffset

class AppHomeHandler(private val config: Config, private val emojiService: EmojiService) {

    companion object {
        enum class Action(val actionId: String) {
            PROPOSALS("home_current_proposals"),
            NEW("home_new_emoji"),
            HELP("home_help"),
            ERRORS("home_recent_errors"),
        }
        fun getActionByName(name: String): Action {
            return when (name) {
                Action.PROPOSALS.actionId -> Action.PROPOSALS
                Action.NEW.actionId -> Action.NEW
                Action.ERRORS.actionId -> Action.ERRORS
                Action.HELP.actionId -> Action.HELP
                else -> Action.PROPOSALS
            }
        }
    }

    private val logger: Logger = LoggerFactory.getLogger(AppHomeHandler::class.java)

    fun handleAppHomeOpened(event: AppHomeOpenedEvent, ctx: EventContext) {
        val tab = if (event.view != null) {
            getActionByName(event.view.privateMetadata)
        } else {
            Action.PROPOSALS
        }

        try {
            val appHomeView = generateHomeView(tab = tab, user = event.user)

            // Update the App Home for the given user
            val res = ctx.client().viewsPublish {
                it
                    .userId(event.user)
                    .hash(event.view?.hash) // To protect against possible race conditions
                    .view(appHomeView)
            }
            if (!res.isOk) {
                logger.error("Error posting app home view: ${res.error} ${res.responseMetadata}")
            }
        } catch (ex: Exception) {
            logger.warn("$ex ${ex.stackTraceToString()}")
        }
    }

    fun handleButtonClick(req: BlockActionRequest, ctx: ActionContext) {
        var tab = Action.PROPOSALS

        for (action in req.payload.actions) {
            if (action.type == "button") {
                tab = getActionByName(action.actionId)
            }
        }

        try {
            val appHomeView = generateHomeView(tab = tab, user = req.payload.user.id)

            // Update the App Home for the given user
            val res = ctx.client().viewsPublish {
                it
                    .userId(req.payload.user.id)
                    .hash(req.payload.view?.hash) // To protect against possible race conditions
                    .view(appHomeView)
            }
            if (!res.isOk) {
                logger.error("Error posting app home view: ${res.error} ${res.responseMetadata}")
            }
        } catch (ex: Exception) {
            logger.warn("${ex.message} ${ex.stackTraceToString()}")
        }
    }

    private fun generateHomeView(tab: Action, user: String): View {
        val homeView = view {
            it.type("home")
            it.privateMetadata(tab.actionId)
            it.blocks {
                generateButtonList(this, tab, user)
                when (tab) {
                    Action.PROPOSALS ->
                        generateProposalList(this, user)
                    Action.NEW ->
                        generateRecentEmojiList(this, user)
                    Action.HELP ->
                        generateHelp(this, user)
                    Action.ERRORS ->
                        generateRecentErrorList(this, user)
                }
            }
        }

        return homeView
    }

    private fun generateButtonList(blocks: LayoutBlockDsl, tab: Action, user: String) {
        blocks.run {
            actions {
                elements {
                    button {
                        actionId(Action.PROPOSALS.actionId)
                        text("Current Proposals")
                        if (tab == Action.PROPOSALS) {
                            style(ButtonStyle.PRIMARY)
                        }
                    }
                    button {
                        actionId(Action.NEW.actionId)
                        text("Recently Added Emoji")
                        if (tab == Action.NEW) {
                            style(ButtonStyle.PRIMARY)
                        }
                    }
                    button {
                        actionId(Action.HELP.actionId)
                        text("Help")
                        if (tab == Action.HELP) {
                            style(ButtonStyle.PRIMARY)
                        }
                    }
                    if (emojiService.userIsAdmin(user)) {
                        button {
                            actionId(Action.ERRORS.actionId)
                            text("Recent Errors")
                            if (tab == Action.ERRORS) {
                                style(ButtonStyle.PRIMARY)
                            } else {
                                style(ButtonStyle.DANGER)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun generateProposalList(blocks: LayoutBlockDsl, user: String) {
        // We only get 100 total blocks.  Each proposal is 3 blocks, plus the headers
        // so we should limit ourselves to 32 results.  Sort the query desc to
        // get the most recent, but then sort the results asc to put the oldest on
        // top.
        val newProposals = emojiService.findProposalsByState(Proposals.STATE_NEW, 32)
            .sortedBy { it.created }

        blocks.run {
            section {
                markdownText("*Current Open Emoji Proposals*")
            }
            for (prop in newProposals) {
                divider()
                section {
                    val tallyResult = emojiService.tallyVotes(prop)
                    val inCommentPeriod =
                        prop.created.plusBusinessDays(config.votes.commentPeriod).isAfter(LocalDateTime.now())
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
                    if (inCommentPeriod) body += ":speech_balloon::clock2: "
                    body += "${tallyResult.up}:${Proposals.UPVOTE}: ${tallyResult.down}:${Proposals.DOWNVOTE}: "
                    if (tallyResult.up - tallyResult.down > config.votes.winBy) body += ":+1: "

                    markdownText(body)
                }

                context {
                    elements {
                        markdownText("by <@${prop.user}> on <!date^${prop.created.toEpochSecond(ZoneOffset.UTC)}^{date} {time}|${prop.created}>")
                        if (emojiService.userIsAdmin(user)) {
                            markdownText("_( ${prop.id} )_")
                        }
                    }
                }
            }
        }
    }

    private fun generateRecentEmojiList(blocks: LayoutBlockDsl, user: String) {
        // We only get 100 total blocks.  Each emoji is 3 blocks, plus the headers
        // so we should limit ourselves to 32 results.  Sort the query desc to
        // get the most recent, but then sort the results asc to put the oldest on
        // top.

        val recentlyAdded = emojiService.getRecentlyAdded()
            .sortedBy { it.second }

        blocks.run {
            section {
                markdownText("*Recently Added Emoji*")
            }

            for (entry in recentlyAdded) {
                val prop = entry.first
                val added = entry.second
                val t = added.toEpochSecond(ZoneOffset.UTC)

                divider()
                section {
                    markdownText("`:${prop.emoji}:` :${prop.emoji}: <${prop.permalink}|Thread>")
                }
                context {
                    elements {
                        markdownText(
                            "Proposed by <@${prop.user}>. Uploaded on <!date^$t^{date} {time}|$added>"
                        )
                        if (emojiService.userIsAdmin(user)) {
                            markdownText("_( ${prop.id} )_")
                        }
                    }
                }
            }
        }
    }

    private fun generateHelp(blocks: LayoutBlockDsl, user: String) {
        blocks.run {
            section {
                markdownText("${config.text.intro}\n${config.text.homeAdvanced}\n${config.text.help.general}")
            }
            divider()
            section {
                markdownText("Key for Proposal List:")
                fields {
                    markdownText(
                        """
                        :heavy_plus_sign:
                        :heavy_minus_sign:
                        :speech_balloon::clock2:
                        :${Proposals.UPVOTE}:
                        :${Proposals.DOWNVOTE}:
                        :+1:
                        """.trimIndent()
                    )
                    markdownText(
                        """
                        Proposal for adding emoji
                        Proposal for removing emoji
                        Proposal still in comment period
                        Number of upvotes
                        Number of downvotes
                        Has enough votes to pass
                        """.trimIndent()
                    )
                }
            }
            if (emojiService.userIsAdmin(user)) {
                divider()
                section {
                    markdownText("*Admin Only Functions*\n${config.text.help.admin}")
                }
            }
            divider()
            section {
                plainText("Version: ${getVersionNumber()}")
            }
        }
    }

    private fun generateRecentErrorList(blocks: LayoutBlockDsl, user: String) {
        // We only get 100 total blocks.  Each log entry is 3 blocks, plus the headers
        // so we should limit ourselves to 32 results.  Sort the query desc to
        // get the most recent, but then sort the results asc to put the oldest on
        // top.

        if (emojiService.userIsAdmin(user)) {
            val recentErrors = emojiService.getRecentErrors()
                .sortedBy { it.second.date }

            blocks.run {
                section {
                    markdownText("*Recent Errors*")
                }

                for (entry in recentErrors) {
                    val proposal = entry.first
                    val auditEntry = entry.second
                    divider()
                    section {
                        markdownText("`:${proposal.emoji}:` <${proposal.permalink}|Thread>")
                    }
                    context {
                        elements {
                            markdownText("${auditEntry.note}")
                            markdownText(
                                "Proposed by <@${proposal.user}>. Failed on <!date^${
                                    auditEntry.date.toEpochSecond(ZoneOffset.UTC)
                                }^{date} {time}|${auditEntry.date}>"
                            )
                            markdownText("_( ${proposal.id} )_")
                        }
                    }
                }
            }
        } else {
            blocks.run {
                section {
                    markdownText("Restricted Feature")
                }
            }
        }
    }
}
