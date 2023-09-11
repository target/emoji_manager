package com.target.slack

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.Masked
import com.sksamuel.hoplite.PropertySource
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.time.LocalDate

data class Config(
    val server: ServerConfig,
    val database: DatabaseConfig,
    val votes: VoteConfig,
    val slack: SlackConfig,
    val text: TextConfig,
)

data class ServerConfig(
    val port: Int, // eg 3000
    val urlPrefix: String, // eg https://api.example.com - used for self-referencing URLs
    val urlPath: String, // eg /api/slack_events
)

data class DatabaseConfig(
    val url: String, // eg jdbc:postgresql://hostname:5432/emojimanager"
    val username: String,
    val password: Masked,
    val adminUsername: String,
    val adminPassword: Masked,
    val autoMigrate: Boolean,
)
data class VoteConfig(
    val commentPeriod: Long = 1, // in business days; how long to wait before vote tallying happens
    val maxDuration: Long = 30, // in business days; how long a proposal can live before it is closed
    val winBy: Int = 5, // How many more
    val tallySchedule: Long = 30, // in minutes; how frequently to tally votes of all open proposals
    // Populate this via config file
    val calendarHolidays: List<LocalDate> = listOf(), // a list of dates in the format of YYYY-MM-DD (2023-01-02) that are considered holidays
    val downVoteThreshold: Int = 5, // How many downvotes before admins are notified
)

data class SlackConfig(
    val slackSigningSecret: Masked,
    val slackAdminToken: Masked,
    val slackAppToken: Masked,
    val slackBotToken: Masked,
    val slackEmojiChannel: String,
    val slackAdminUsers: List<String>,
    val slackEmojiAdminChannel: String,
    val slackProposalChannels: List<String>,
    val slackHintChannels: List<String>,
)

data class TextConfig(
    val intro: String,
    val homeAdvanced: String,
    val help: HelpText,

    val upvote: String = "white_check_mark", // changing these from the defaults is untested and may not work correctly
    val downvote: String = "x",
    val force: String = "large_green_circle",
    val block: String = "no_entry_sign",
    val withdraw: String = "rewind",
    val report: String = "small_orange_diamond",
)

data class HelpText(
    val general: String = "",
    val admin: String = "",
    val cmdStatus: String = "",
    val cmdAlias: String = "",
    val cmdRemove: String = "",
    val cmdReset: String = "",
    val cmdTally: String = "",
    val cmdFakevote: String = "",
)

/**
 * The sources are first-in-wins, so define them in order of highest priority descending.
 */
fun buildOrderedSources(): List<PropertySource> {
    // Not explicitly added here, but included by default with `ConfigLoaderBuilder.default()` are environment variable
    // and system property overrides. Sample expected naming convention to use when adding overrides:
    // System property: `config.override.foo` to override a config variable named foo
    // Environment property: `config.override.foo to override a config variables named foo.
    // Note that they are both lowercase by default and use a config.override prefix to avoid collisions with existing OS variables.

    val sources = mutableListOf<PropertySource>()

    if (System.getenv().containsKey("CI")) {
        // running in a build
        sources.add(PropertySource.resource("/ci.yaml", optional = true))
        sources.add(PropertySource.resource("/ci.conf", optional = true))
    }

    // running locally - let developers keep a local.conf file for configuration overrides.
    sources.add(PropertySource.resource("/local.yaml", optional = true))
    sources.add(PropertySource.resource("/local.conf", optional = true))
    sources.add(PropertySource.file(File("local.yaml"), optional = true))
    sources.add(PropertySource.file(File("local.conf"), optional = true))

    if (System.getenv().containsKey("EMOJI_MANAGER_CONFIG_FILES")) {
        for (fileName in System.getenv("EMOJI_MANAGER_CONFIG_FILES").split(Regex("[, ]+"))) {
            sources.add(PropertySource.file(File(fileName), optional = true))
        }
    }

    // finally add the default.conf file to the configuration sources
    sources.add(PropertySource.resource("/default.yaml"))
    sources.add(PropertySource.resource("/default.conf"))

    return sources
}

/**
 * Load config for the given class destination
 */
inline fun <reified T> loadConfig(): T {
    val configBuilder = ConfigLoaderBuilder
        .default()
        .allowUnresolvedSubstitutions()

    val logger: Logger = LoggerFactory.getLogger(Config::class.java)

    buildOrderedSources().forEach { propertySource ->
        logger.info("Loading config from ${propertySource.source()}")
        configBuilder.addSource(propertySource)
    }
    try {
        return configBuilder
            .build()
            .loadConfigOrThrow()
    } catch (t: Throwable) {
        // configuration errors were getting swallowed in CI, so adding some explicit logging
        t.printStackTrace()
        throw t
    }
}
