package com.target.slack

import com.slack.api.bolt.context.builtin.WorkflowStepEditContext
import com.slack.api.bolt.context.builtin.WorkflowStepExecuteContext
import com.slack.api.bolt.context.builtin.WorkflowStepSaveContext
import com.slack.api.bolt.middleware.builtin.WorkflowStep
import com.slack.api.bolt.request.builtin.WorkflowStepEditRequest
import com.slack.api.bolt.request.builtin.WorkflowStepExecuteRequest
import com.slack.api.bolt.request.builtin.WorkflowStepSaveRequest
import com.slack.api.model.block.Blocks
import com.slack.api.model.block.composition.BlockCompositions
import com.slack.api.model.block.element.BlockElements
import com.slack.api.model.view.ViewState
import com.slack.api.model.workflow.WorkflowStepInput
import com.slack.api.model.workflow.WorkflowSteps
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class WorkflowHandler(private val config: Config, private val emojiService: EmojiService) {

    private var logger: Logger = LoggerFactory.getLogger(WorkflowHandler::class.java)

    fun getWorkflowStepAlias(): WorkflowStep {
        val step = WorkflowStep.builder()
            .callbackId("propose_alias_emoji")
            .edit { _: WorkflowStepEditRequest?, ctx: WorkflowStepEditContext ->
                ctx.configure(
                    Blocks.asBlocks(
                        Blocks.section { s ->
                            s.blockId("intro-section")
                            s.text(BlockCompositions.markdownText("Propose an emoji alias in the <#${config.slack.slackEmojiChannel}> channel"))
                        },
                        Blocks.input { i ->
                            i.blockId("original_emoji_name_input")
                            i.label(BlockCompositions.plainText("Original Emoji Name"))
                            i.element(BlockElements.plainTextInput { pti -> pti.actionId("original_emoji_name") })
                        },
                        Blocks.input { i ->
                            i.blockId("alias_name_input")
                            i.label(BlockCompositions.plainText("Emoji Alias Name"))
                            i.element(BlockElements.plainTextInput { pti -> pti.actionId("alias_name") })
                        },
                        Blocks.input { i ->
                            i.blockId("submitter_input")
                            i.label(BlockCompositions.plainText("Submitter"))
                            i.element(BlockElements.plainTextInput { pti -> pti.actionId("submitter") })
                        },
                    )
                )
                ctx.ack()
            }
            .save { req: WorkflowStepSaveRequest?, ctx: WorkflowStepSaveContext ->
                val stateValues: Map<String, Map<String, ViewState.Value>> = req!!.payload.view.state.values
                val inputs: HashMap<String, WorkflowStepInput> = HashMap()
                inputs["original_emoji_name"] =
                    WorkflowSteps.stepInput { i -> i.value(stateValues["original_emoji_name_input"]!!["original_emoji_name"]!!.value) }
                inputs["alias_name"] =
                    WorkflowSteps.stepInput { i -> i.value(stateValues["alias_name_input"]!!["alias_name"]!!.value) }
                inputs["submitter"] =
                    WorkflowSteps.stepInput { i -> i.value(stateValues["submitter_input"]!!["submitter"]!!.value) }

                val outputs = WorkflowSteps.asStepOutputs(
                    WorkflowSteps.stepOutput { o ->
                        o.name("original_emoji_name")
                        o.type("text")
                        o.label("Original Emoji Name")
                    },
                    WorkflowSteps.stepOutput { o ->
                        o.name("alias_name")
                        o.type("text")
                        o.label("Emoji Alias Name")
                    },
                    WorkflowSteps.stepOutput { o ->
                        o.name("proposal_id")
                        o.type("text")
                        o.label("Emoji Proposal ID")
                    },
                    WorkflowSteps.stepOutput { o ->
                        o.name("conversation_link")
                        o.type("text")
                        o.label("Link to proposal conversation")
                    }
                )
                ctx.update(inputs, outputs)
                ctx.ack()
            }
            .execute { req: WorkflowStepExecuteRequest, ctx: WorkflowStepExecuteContext ->
                checkNotNull(req.payload.event.workflowStep)
                val wfStep = req.payload.event.workflowStep

                val emojiname = wfStep.inputs?.get("original_emoji_name")?.value as String
                val aliasname = wfStep.inputs?.get("alias_name")?.value as String
                var requester = wfStep.inputs?.get("submitter")?.value as String

                if (requester.startsWith("<@")) {
                    requester = requester.substring(2, requester.length - 1)
                }

                emojiService.handleAliasFunction(ctx, requester, emojiname, aliasname)
                    .onSuccess { prop ->
                        logger.debug("Workflow step successfully created alias proposal. Sending outputs")
                        val outputs: HashMap<String, Any> = HashMap()
                        outputs["proposal_id"] = prop.id.toString()
                        outputs["original_emoji_name"] = emojiname
                        outputs["alias_name"] = aliasname
                        outputs["conversation_link"] = ctx.client().chatGetPermalink {
                            it.channel(config.slack.slackEmojiChannel)
                            it.messageTs(prop.thread)
                        }.permalink

                        ctx.complete(outputs.toMap())
                    }
                    .onFailure { ex ->
                        logger.error("Workflow step failed to create alias proposal. ${ex.message}")
                        val error: MutableMap<String, Any> = HashMap()
                        error["message"] = ex.message as Any
                        ctx.fail(error)
                    }

                ctx.ack()
            }
            .build()
        return step
    }

    fun getWorkflowStepRemove(): WorkflowStep {
        val step = WorkflowStep.builder()
            .callbackId("propose_delete_emoji")
            .edit { _: WorkflowStepEditRequest?, ctx: WorkflowStepEditContext ->
                ctx.configure(
                    Blocks.asBlocks(
                        Blocks.section { s ->
                            s.blockId("intro-section")
                            s.text(BlockCompositions.markdownText("Propose an emoji to delete in the <#${config.slack.slackEmojiChannel}> channel"))
                        },
                        Blocks.input { i ->
                            i.blockId("emoji_name_input")
                            i.label(BlockCompositions.plainText("Emoji Name"))
                            i.element(BlockElements.plainTextInput { pti -> pti.actionId("emoji_name") })
                        },
                        Blocks.input { i ->
                            i.blockId("submitter_input")
                            i.label(BlockCompositions.plainText("Submitter"))
                            i.element(BlockElements.plainTextInput { pti -> pti.actionId("submitter") })
                        },
                    )
                )
                ctx.ack()
            }
            .save { req: WorkflowStepSaveRequest?, ctx: WorkflowStepSaveContext ->
                val stateValues: Map<String, Map<String, ViewState.Value>> = req!!.payload.view.state.values
                val inputs: HashMap<String, WorkflowStepInput> = HashMap()
                inputs["emoji_name"] =
                    WorkflowSteps.stepInput { i -> i.value(stateValues["emoji_name_input"]!!["emoji_name"]!!.value) }
                inputs["submitter"] =
                    WorkflowSteps.stepInput { i -> i.value(stateValues["submitter_input"]!!["submitter"]!!.value) }
                val outputs = WorkflowSteps.asStepOutputs(
                    WorkflowSteps.stepOutput { o ->
                        o.name("emoji_name")
                        o.type("text")
                        o.label("Emoji Name")
                    },
                    WorkflowSteps.stepOutput { o ->
                        o.name("proposal_id")
                        o.type("text")
                        o.label("Emoji Proposal ID")
                    },
                    WorkflowSteps.stepOutput { o ->
                        o.name("conversation_link")
                        o.type("text")
                        o.label("Link to proposal conversation")
                    }
                )
                ctx.update(inputs, outputs)
                ctx.ack()
            }
            .execute { req: WorkflowStepExecuteRequest, ctx: WorkflowStepExecuteContext ->
                checkNotNull(req.payload.event.workflowStep)
                val wfStep = req.payload.event.workflowStep

                val emojiname = wfStep.inputs?.get("emoji_name")?.value as String
                var requester = wfStep.inputs?.get("submitter")?.value as String

                if (requester.startsWith("<@")) {
                    requester = requester.substring(2, requester.length - 1)
                }

                emojiService.handleRemoveFunction(ctx, requester, emojiname)
                    .onSuccess { prop ->
                        logger.debug("Workflow step successfully created remove proposal. Sending outputs")
                        val outputs: HashMap<String, Any> = HashMap()
                        outputs["proposal_id"] = prop.id.toString()
                        outputs["emoji_name"] = emojiname
                        outputs["conversation_link"] = ctx.client().chatGetPermalink {
                            it.channel(config.slack.slackEmojiChannel)
                            it.messageTs(prop.thread)
                        }.permalink
                        ctx.complete(outputs.toMap())
                    }
                    .onFailure { ex ->
                        logger.error("Workflow step failed to create remove proposal. ${ex.message}")
                        val error: MutableMap<String, Any> = HashMap()
                        error["message"] = ex.message as Any
                        ctx.fail(error)
                    }

                ctx.ack()
            }
            .build()
        return step
    }
}
