package com.target.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.AppConfig
import com.slack.api.bolt.socket_mode.SocketModeApp
import com.slack.api.model.event.AppHomeOpenedEvent
import com.slack.api.model.event.MessageFileShareEvent
import com.slack.api.model.event.ReactionAddedEvent
import com.slack.api.model.event.ReactionRemovedEvent
import com.target.liteforjdbc.Db
import com.target.liteforjdbc.DbConfig
import org.flywaydb.core.Flyway
import org.http4k.server.SunHttp
import org.http4k.server.asServer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SlackApplication(private val config: Config, db: Db) {

    private var logger: Logger = LoggerFactory.getLogger(SlackApplication::class.java)

    private val corePoolSize = 2
    private val maximumPoolSize = corePoolSize * 2
    private val keepAliveTime = 100L
    private val workQueue = SynchronousQueue<Runnable>()
    private val workerPool: ExecutorService = ThreadPoolExecutor(
        corePoolSize,
        maximumPoolSize,
        keepAliveTime,
        TimeUnit.SECONDS,
        workQueue
    )
    private val worker = Executors.newSingleThreadScheduledExecutor()

    private val emojiService = EmojiService(config, db)
    private val commandHandler = SlashCommandHandler(config, emojiService)
    private val reactionHandler = ReactionHandler(config, emojiService)
    private val messageHandler = MessageHandler(config, emojiService)
    private val workflowHandler = WorkflowHandler(config, emojiService)
    private val appHomeHandler = AppHomeHandler(config, emojiService)

    fun initBoltApp(): App {
        val appConfig: AppConfig = AppConfig.builder()
            .signingSecret(config.slack.slackSigningSecret.value)
            .singleTeamBotToken(config.slack.slackBotToken.value)
            .allEventsApiAutoAckEnabled(true)
            .subtypedMessageEventsAutoAckEnabled(true)
            .build()

        val app = App(appConfig)

        app.command("/emoji") { req, ctx ->
            workerPool.submit { commandHandler.handleCommand(req, ctx) }
            logger.debug("slash command event received")
            ctx.ack()
        }

        app.step(workflowHandler.getWorkflowStepAlias())
        app.step(workflowHandler.getWorkflowStepRemove())

        app.event(ReactionAddedEvent::class.java) { eventPayload, ctx ->
            workerPool.submit { reactionHandler.handleReaction(eventPayload.event, ctx) }
            logger.debug("reaction added event received")
            ctx.ack()
        }

        app.event(ReactionRemovedEvent::class.java) { eventPayload, ctx ->
            workerPool.submit { reactionHandler.handleReactionRemoved(eventPayload.event, ctx) }
            logger.debug("reaction removed event received")
            ctx.ack()
        }

        app.event(MessageFileShareEvent::class.java) { eventPayload, ctx ->
            workerPool.submit { messageHandler.handleMessage(eventPayload.event, ctx) }
            logger.debug("message file share event received")
            ctx.ack()
        }

        app.event(AppHomeOpenedEvent::class.java) { eventPayload, ctx ->
            workerPool.submit { appHomeHandler.handleAppHomeOpened(eventPayload.event, ctx) }
            logger.debug("app home opened event received")
            ctx.ack()
        }

        for (action in enumValues<AppHomeHandler.Companion.Action>()) {
            app.blockAction(action.actionId) { req, ctx ->
                workerPool.submit { appHomeHandler.handleButtonClick(req, ctx) }
                logger.debug("app home button clicked: ${action.actionId}")
                ctx.ack()
            }
        }

        app.blockAction(MessageHandler.PROPOSE_BUTTON) { req, ctx ->
            workerPool.submit { messageHandler.handleProposeButtonClick(req, ctx) }
            logger.debug("message button clicked")
            ctx.ack()
        }

        app.viewSubmission(MessageHandler.SENDIT_FORM) { req, ctx ->
            workerPool.submit { messageHandler.handleSenditFormSubmit(req, ctx) }
            logger.debug("sendit form submitted")
            ctx.ack()
        }

        app.viewClosed(MessageHandler.SENDIT_FORM) { req, ctx ->
            logger.debug("sendit form canceled")
            ctx.ack()
        }

        // TODO: using scheduled works well for a single-instance, but if in a cluster we dont need 5 instances doing the tally work
        try {
            worker.scheduleAtFixedRate(
                { emojiService.tallyVotes() },
                config.votes.tallySchedule,
                config.votes.tallySchedule,
                TimeUnit.MINUTES
            )
        } catch (t: Throwable) {
            logger.error(t.message)
            Runtime.getRuntime().halt(1)
        }

        return app
    }
}

fun main() {
    println("Starting version ${getVersionNumber()}")
    val config = loadConfig<Config>()

    if (config.database.autoMigrate) {
        val flyway = Flyway.configure()
            .dataSource(config.database.url, config.database.adminUsername, config.database.adminPassword.value)
            .load()
        flyway.migrate()
    }
    val db = Db(
        dbConfigFromUrl(
            DbConfig(
                databaseName = "emojimanagerdev",
                jdbcUrl = config.database.url,
                username = config.database.username,
                password = config.database.password.value,
            )
        )
    )
    val slackApp = SlackApplication(config, db)
    val boltApp = slackApp.initBoltApp()

    val http4KEmojiManagerApp = Http4KEmojiManagerApp(config, boltApp, config.server.urlPath + "_events", db)

    http4KEmojiManagerApp.asServer(SunHttp(config.server.port)).start()

    // This is for local testing only. Eventually get rid of socket mode here
    if (config.slack.slackAppToken.value.isEmpty()) {
        val socket = SocketModeApp(boltApp)
        socket.start()
    }
}
