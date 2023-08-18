package com.target.slack

import com.slack.api.Slack
import com.slack.api.bolt.context.builtin.EventContext
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class ReactionHandler(private val config: Config, private val emojiService: EmojiService) {

    private var logger: Logger = LoggerFactory.getLogger(ReactionHandler::class.java)

    fun handleReaction(event: ReactionAddedEvent, ctx: EventContext) {
        val action: String = if (event.reaction == Proposals.UPVOTE) {
            AuditLog.ACTION_VOTE_UP
        } else if (event.reaction == Proposals.DOWNVOTE) {
            AuditLog.ACTION_VOTE_DN
        } else if (event.reaction == Proposals.FORCE && emojiService.userIsAdmin(event.user)) {
            return handleForce(event, ctx)
        } else if (event.reaction == Proposals.BLOCK && emojiService.userIsAdmin(event.user)) {
            return handleBlock(event, ctx)
        } else if (event.reaction == Proposals.WITHDRAW) {
            return handleWithdraw(event, ctx)
        } else {
            return // ignore reaction types we don't care about
        }

        emojiService.recordVote(event.item.ts, event.user, action)
    }

    fun handleReactionRemoved(event: ReactionRemovedEvent, ctx: EventContext) {
        // REMOVING a reaction is the same as adding a counteracting vote. So just flip the logic.
        val action: String = if (event.reaction == Proposals.UPVOTE) {
            AuditLog.ACTION_VOTE_DN
        } else if (event.reaction == Proposals.DOWNVOTE) {
            AuditLog.ACTION_VOTE_UP
        } else if (event.reaction == Proposals.FORCE && emojiService.userIsAdmin(event.user)) {
            return handleUnforce(event, ctx)
        } else if (event.reaction == Proposals.BLOCK && emojiService.userIsAdmin(event.user)) {
            return handleUnblock(event, ctx)
        } else if (event.reaction == Proposals.WITHDRAW) {
            return handleUnwithdraw(event, ctx)
        } else {
            return // ignore reaction types we don't care about
        }

        emojiService.recordVote(event.item.ts, event.user, action)
    }

    private fun handleForce(event: ReactionAddedEvent, ctx: EventContext) {
        val adminClient = Slack.getInstance().methodsAsync(config.slack.slackAdminToken.value)
        val prop = emojiService.findProposalByThread(event.item.ts)

        if (prop != null) {
            if (prop.state == Proposals.STATE_NEW) {
                emojiService.recordVote(event.item.ts, event.user, AuditLog.ACTION_ADMIN_FORCE)
                logger.warn("${event.user} forced proposal ${prop.id}")
                if (prop.action == Proposals.ACTION_ADD) {
                    if (prop.alias) {
                        ctx.client().chatPostMessage { p ->
                            p.channel(event.item.channel)
                            p.text("An admin is creating this alias right away. One moment, please.")
                            p.threadTs(event.item.ts)
                        }
                        val a = emojiService.createAlias(adminClient, prop)
                        if (a.ok) {
                            ctx.client().chatPostMessage { p ->
                                p.channel(config.slack.slackEmojiChannel)
                                p.text(":${prop.emoji}: has been created.")
                                p.threadTs(prop.thread)
                                p.replyBroadcast(true)
                            }
                        } else {
                            ctx.client().chatPostMessage { p ->
                                p.channel(config.slack.slackEmojiChannel)
                                p.text("Oh no! Something went wrong with the alias. Please have an admin. look into this.\nProposal id ${prop.id}\nError: `${a.error}`")
                                p.threadTs(prop.thread)
                            }
                        }
                    } else {
                        ctx.client().chatPostMessage { p ->
                            p.channel(event.item.channel)
                            p.text("An admin is uploading this emoji right away. One moment, please.")
                            p.threadTs(event.item.ts)
                        }
                        val a = emojiService.uploadEmoji(adminClient, prop)
                        if (a.ok) {
                            ctx.client().chatPostMessage { p ->
                                p.channel(config.slack.slackEmojiChannel)
                                p.text(":${prop.emoji}: has been uploaded.")
                                p.threadTs(prop.thread)
                                p.replyBroadcast(true)
                            }
                        } else {
                            ctx.client().chatPostMessage { p ->
                                p.channel(config.slack.slackEmojiChannel)
                                p.text("Oh no! Something went wrong with the upload. Please have an admin. look into this.\nProposal id ${prop.id}\nError: `${a.error}`")
                                p.threadTs(prop.thread)
                            }
                        }
                    }
                } else {
                    ctx.client().chatPostMessage { p ->
                        p.channel(event.item.channel)
                        p.text("An admin is removing this emoji right away. One moment, please.")
                        p.threadTs(event.item.ts)
                    }
                    val a = emojiService.removeEmoji(adminClient, prop)
                    if (a.ok) {
                        ctx.client().chatPostMessage { p ->
                            p.channel(config.slack.slackEmojiChannel)
                            p.text(":${prop.emoji}: has been removed.")
                            p.threadTs(prop.thread)
                            p.replyBroadcast(true)
                        }
                    } else {
                        ctx.client().chatPostMessage { p ->
                            p.channel(config.slack.slackEmojiChannel)
                            p.text("Oh no! Something went wrong with the removal. Please have an admin. look into this.\nProposal id ${prop.id}\nError: `${a.error}`")
                            p.threadTs(prop.thread)
                        }
                    }
                }
            } else {
                logger.info("${event.user} used the force vote on ${prop.id}, but state is not ${Proposals.STATE_NEW}")
            }
        } // else: reaction isn't on a proposal
    }

    private fun handleUnforce(event: ReactionRemovedEvent, ctx: EventContext) {
        val prop = emojiService.findProposalByThread(event.item.ts)
        if (prop == null) {
            // nothing to see here
            return
        }
        ctx.client().chatPostEphemeral { p ->
            p.channel(event.item.channel)
            p.text("You already forced voted this emoji. Unreacting at this point has no meaning.")
            p.threadTs(event.item.ts)
        }
        logger.warn("${event.user} tried to un-force a proposal ${prop.id}")
    }

    private fun handleBlock(event: ReactionAddedEvent, ctx: EventContext) {
        val prop = emojiService.findProposalByThread(event.item.ts)
        if (prop == null) {
            // nothing to see here
            return
        }
        emojiService.recordVote(event.item.ts, event.user, AuditLog.ACTION_ADMIN_BLOCK)
        ctx.client().chatPostMessage { p ->
            p.channel(event.item.channel)
            p.text("<@${event.user}> has blocked this proposal. Voting may continue, but the votes will not be counted unless <@${event.user}> unblocks this proposal before the voting period ends.")
            p.threadTs(event.item.ts)
        }
    }

    private fun handleUnblock(event: ReactionRemovedEvent, ctx: EventContext) {
        val prop = emojiService.findProposalByThread(event.item.ts)
        if (prop == null) {
            // nothing to see here
            return
        }
        emojiService.recordVote(event.item.ts, event.user, AuditLog.ACTION_ADMIN_UNBLOCK)
        ctx.client().chatPostMessage { p ->
            p.channel(event.item.channel)
            p.text("<@${event.user}> has unblocked this proposal.")
            p.threadTs(event.item.ts)
        }
    }

    private fun handleWithdraw(event: ReactionAddedEvent, ctx: EventContext) {
        val prop = emojiService.findProposalByThread(event.item.ts)

        if (prop == null || prop.state != Proposals.STATE_NEW) {
            // nothing to see here
            return
        }
        if (event.user.equals(prop.user)) {
            emojiService.withdrawProposal(event.item.ts)

            ctx.client().chatPostMessage { p ->
                p.channel(event.item.channel)
                p.text("<@${event.user}> has withdrawn this proposal; it will no longer be considered.")
                p.threadTs(event.item.ts)
            }
        }
    }

    private fun handleUnwithdraw(event: ReactionRemovedEvent, ctx: EventContext) {
        val prop = emojiService.findProposalByThread(event.item.ts)
        if (prop == null || prop.user != event.user) {
            // nothing to see here
            return
        }
        ctx.client().chatPostEphemeral { p ->
            p.channel(event.item.channel)
            p.text("You already withdrew this emoji. Unreacting at this point has no meaning.")
            p.threadTs(event.item.ts)
        }
        logger.warn("${event.user} tried to un-withdraw a proposal ${prop.id}")
    }
}
