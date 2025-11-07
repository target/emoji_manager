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
 * Perform simple placeholder interpolation of ${...} tokens within the TextConfig strings
 * using values from the loaded config. This runs several iterations to resolve nested substitutions.
 */
fun interpolateTextConfig(cfg: Config): TextConfig {
    // Build map of keys to string values we support for interpolation
    val replacements = mutableMapOf<String, String>()
    // slack
    replacements["slack.slackEmojiChannel"] = cfg.slack.slackEmojiChannel
    // votes
    replacements["votes.winBy"] = cfg.votes.winBy.toString()
    replacements["votes.maxDuration"] = cfg.votes.maxDuration.toString()
    replacements["votes.commentPeriod"] = cfg.votes.commentPeriod.toString()
    // text simple tokens
    replacements["text.withdraw"] = cfg.text.withdraw
    replacements["text.report"] = cfg.text.report
    replacements["text.force"] = cfg.text.force
    replacements["text.block"] = cfg.text.block
    // help commands and other text.help keys (populate from HelpText in a compact way)
    replacements["text.help.cmdVersion"] = cfg.text.help.cmdStatus // fallback if not defined; we'll update as we expand

    val helpMap = mapOf(
        "text.help.cmdStatus" to cfg.text.help.cmdStatus,
        "text.help.cmdAlias" to cfg.text.help.cmdAlias,
        "text.help.cmdRemove" to cfg.text.help.cmdRemove,
        "text.help.cmdReset" to cfg.text.help.cmdReset,
        "text.help.cmdTally" to cfg.text.help.cmdTally,
        "text.help.cmdFakevote" to cfg.text.help.cmdFakevote,
        "text.help.general" to cfg.text.help.general,
        "text.help.admin" to cfg.text.help.admin,
    )
    replacements.putAll(helpMap)

    // Start with current raw strings
    var intro = cfg.text.intro
    var homeAdvanced = cfg.text.homeAdvanced
    var general = cfg.text.help.general
    var admin = cfg.text.help.admin
    var cmdStatus = cfg.text.help.cmdStatus
    var cmdAlias = cfg.text.help.cmdAlias
    var cmdRemove = cfg.text.help.cmdRemove
    var cmdReset = cfg.text.help.cmdReset
    var cmdTally = cfg.text.help.cmdTally
    var cmdFakevote = cfg.text.help.cmdFakevote

    // Helper to replace all ${key} occurrences using replacements map; precompile regex once
    val placeholderRegex = "\\$\\{([^}]+)}".toRegex()
    fun replaceAll(s: String): String {
        return placeholderRegex.replace(s) { match ->
            val key = match.groupValues[1]
            replacements[key] ?: match.value // if unknown, keep original to allow other passes to resolve
        }
    }

    // Iteratively replace to allow nested references (limit to 5 passes)
    for (i in 0..4) {
        // update replacements with any help fields that may themselves contain placeholders
        val dynamicHelp = mapOf(
            "text.help.cmdStatus" to cmdStatus,
            "text.help.cmdAlias" to cmdAlias,
            "text.help.cmdRemove" to cmdRemove,
            "text.help.cmdReset" to cmdReset,
            "text.help.cmdTally" to cmdTally,
            "text.help.cmdFakevote" to cmdFakevote,
        )
        replacements.putAll(dynamicHelp)
        // ensure cmdVersion has a sensible fallback
        replacements["text.help.cmdVersion"] = replacements["text.help.cmdVersion"] ?: cmdStatus

        val newIntro = replaceAll(intro)
        val newHome = replaceAll(homeAdvanced)
        val newGeneral = replaceAll(general)
        val newAdmin = replaceAll(admin)
        val newCmdStatus = replaceAll(cmdStatus)
        val newCmdAlias = replaceAll(cmdAlias)
        val newCmdRemove = replaceAll(cmdRemove)
        val newCmdReset = replaceAll(cmdReset)
        val newCmdTally = replaceAll(cmdTally)
        val newCmdFakevote = replaceAll(cmdFakevote)

        val changed = (newIntro != intro) || (newHome != homeAdvanced) || (newGeneral != general) ||
            (newAdmin != admin) || (newCmdStatus != cmdStatus) || (newCmdAlias != cmdAlias) ||
            (newCmdRemove != cmdRemove) || (newCmdReset != cmdReset) || (newCmdTally != cmdTally) ||
            (newCmdFakevote != cmdFakevote)

        intro = newIntro
        homeAdvanced = newHome
        general = newGeneral
        admin = newAdmin
        cmdStatus = newCmdStatus
        cmdAlias = newCmdAlias
        cmdRemove = newCmdRemove
        cmdReset = newCmdReset
        cmdTally = newCmdTally
        cmdFakevote = newCmdFakevote

        if (!changed) break
    }

    val newHelp = HelpText(
        general = general,
        admin = admin,
        cmdStatus = cmdStatus,
        cmdAlias = cmdAlias,
        cmdRemove = cmdRemove,
        cmdReset = cmdReset,
        cmdTally = cmdTally,
        cmdFakevote = cmdFakevote,
    )

    return TextConfig(
        intro = intro,
        homeAdvanced = homeAdvanced,
        help = newHelp,
        upvote = cfg.text.upvote,
        downvote = cfg.text.downvote,
        force = cfg.text.force,
        block = cfg.text.block,
        withdraw = cfg.text.withdraw,
        report = cfg.text.report,
    )
}

/**
 * Load config for the given class destination
 */
@Suppress("DEPRECATION")
inline fun <reified T : Any> loadConfig(): T {
    val configBuilder = ConfigLoaderBuilder
        .default()
        .allowUnresolvedSubstitutions()

    val logger: Logger = LoggerFactory.getLogger(Config::class.java)

    buildOrderedSources().forEach { propertySource ->
        logger.info("Loading config from ${propertySource.source()}")
        configBuilder.addSource(propertySource)
    }
    try {
        val loaded = configBuilder
            .build()
            .loadConfigOrThrow<T>()

        // Only interpolate TextConfig values when we've loaded a Config (safest to check)
        if (loaded is Config) {
            val cfg = loaded
            val interpolatedText = interpolateTextConfig(cfg)
            // return a copy of the loaded config with interpolated text
            @Suppress("UNCHECKED_CAST")
            return (cfg.copy(text = interpolatedText) as T)
        }

        return loaded
    } catch (t: Throwable) {
        // configuration errors were getting swallowed in CI, so adding some explicit logging
        t.printStackTrace()
        throw t
    }
}
