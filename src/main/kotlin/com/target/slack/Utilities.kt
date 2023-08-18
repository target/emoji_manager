package com.target.slack

import com.slack.api.bolt.context.Context
import com.slack.api.methods.MethodsClient
import com.target.liteforjdbc.DbConfig
import com.target.liteforjdbc.DbType
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

const val ALIAS = "alias:"
const val BUILT_IN = "built-in"

// Slack emoji name rule, a best we can discover
val slackNameRegex = "^[-+_a-z0-9]+$".toRegex()

data class ActionResult(val ok: Boolean, val error: String?)
fun LocalDateTime.isWeekend(): Boolean = (dayOfWeek === DayOfWeek.SATURDAY || dayOfWeek === DayOfWeek.SUNDAY)

fun LocalDateTime.plusBusinessDays(days: Long, calendarHolidays: List<LocalDate> = listOf()): LocalDateTime {
    var daysLeft = days
    var result: LocalDateTime = this
    while (daysLeft > 0) {
        result = result.plusDays(1)
        if (!(result.isWeekend() || calendarHolidays.contains(result.toLocalDate()))) {
            daysLeft--
        }
    }
    return result
}

fun hashString(type: String, input: ByteArray): String {
    val hexChars = "0123456789ABCDEF"
    val bytes = MessageDigest
        .getInstance(type)
        .digest(input)
    val result = StringBuilder(bytes.size * 2)

    bytes.forEach {
        val i = it.toInt()
        result.append(hexChars[i shr 4 and 0x0f])
        result.append(hexChars[i and 0x0f])
    }

    return result.toString()
}

fun stripExtension(fileName: String): Pair<String, String> {
    // Kotlin doesn't have a built-in way to split starting from the end
    // So we reverse the string, split from the beginning, and reverse before
    // returning again.
    // Also lowercase it- slack doesn't support uppercase names
    val t = fileName.reversed()
    val s = t.split(".", ignoreCase = false, limit = 2)
    if (s.size == 2) {
        return Pair(s[1].reversed().lowercase(), s[0].reversed().lowercase())
    }
    return Pair(s[0].reversed().lowercase(), "")
}

fun MethodsClient.getAllEmoji(): Map<String, String> {
    val emojiList = emojiList { it.includeCategories(true) }
    val liveEmoji = emojiList.emoji
    for (cat in emojiList.categories) {
        for (name in cat.emojiNames) {
            liveEmoji[name] = BUILT_IN
        }
    }
    return liveEmoji
}

fun getVersionNumber(): String {
    val classVersion = SlackApplication::class.java.`package`?.implementationVersion
    return classVersion ?: "local build"
}

fun dbConfigFromUrl(dbConfig: DbConfig): DbConfig {
    var type = dbConfig.type
    var host = dbConfig.host
    var port = dbConfig.port
    var username = dbConfig.username
    var password = dbConfig.password
    var databaseName = dbConfig.databaseName
    var jdbcUrl = dbConfig.jdbcUrl
    var ssl = dbConfig.ssl
    var connectionTimeoutMillis = dbConfig.connectionTimeoutMillis
    var idleTimeoutMillis = dbConfig.idleTimeoutMillis
    var keepAliveTime = dbConfig.keepAliveTime
    var maxLifetime = dbConfig.maxLifetime
    var minimumIdle = dbConfig.minimumIdle
    var maximumPoolSize = dbConfig.maximumPoolSize

    if (jdbcUrl != null) {
        if (!jdbcUrl.startsWith("jdbc:")) {
            throw Exception("Invalid JDBC URL format")
        }
    } else {
        return dbConfig
    }

    var tokens = jdbcUrl.split("//", limit = 2)
    type = getJdbcType(URLDecoder.decode(tokens[0], "utf-8"))

    if (tokens.size > 1) {
        tokens = tokens[1].split("/", limit = 2)
        val hostParts = tokens[0].split(":")
        host = URLDecoder.decode(hostParts[0], "utf-8")
        if (hostParts.size > 1) {
            port = hostParts[1].toInt()
        }
        if (tokens.size > 1) {
            tokens = tokens[1].split("?", limit = 2)
            databaseName = URLDecoder.decode(tokens[0], "utf-8")
            if (tokens.size > 1) {
                val options = tokens[1].split("&")
                for (optpair in options) {
                    val opt = optpair.split("=", limit = 2)
                    if (opt.size == 2) {
                        when (opt[0]) {
                            "user" -> username = URLDecoder.decode(opt[1], "utf-8")
                            "password" -> password = URLDecoder.decode(opt[1], "utf-8")
                            "ssl" -> ssl = opt[1].toBoolean()
                            "connectionTimeoutMillis" -> connectionTimeoutMillis = opt[1].toLong()
                            "idleTimeoutMillis" -> idleTimeoutMillis = opt[1].toLong()
                            "keepAliveTime" -> keepAliveTime = opt[1].toLong()
                            "maxLifetime" -> maxLifetime = opt[1].toLong()
                            "minimumIdle" -> minimumIdle = opt[1].toInt()
                            "maximumPoolSize" -> maximumPoolSize = opt[1].toInt()
                        }
                    }
                }
            }
        }
    }

    return DbConfig(
        type = type,
        host = host,
        port = port,
        username = username,
        password = password,
        databaseName = databaseName,
        ssl = ssl,
        connectionTimeoutMillis = connectionTimeoutMillis,
        idleTimeoutMillis = idleTimeoutMillis,
        keepAliveTime = keepAliveTime,
        maxLifetime = maxLifetime,
        minimumIdle = minimumIdle,
        maximumPoolSize = maximumPoolSize
    )
}

private val typeMapping = mutableMapOf(
    "jdbc:h2" to DbType.H2_FILE,
    "jdbc:h2:file" to DbType.H2_FILE,
    "jdbc:h2:mem" to DbType.H2_INMEM,
    "jdbc:postgresql" to DbType.POSTGRES,
    "jdbc:pgsql" to DbType.POSTGRES,
)

private fun getJdbcType(url: String) = typeMapping.entries.firstOrNull { (prefix, _) ->
    url.startsWith(prefix)
}?.value ?: error("Database driver not found for $url")

fun downloadFile(url: String, ctx: Context): ByteArray {
    val client = ctx.slack.httpClient
    val resp = client.get(url, null, ctx.botToken)
    if (resp.isSuccessful) {
        return resp.body?.bytes() ?: ByteArray(0)
    }
    throw Exception("Failed to download file: ${resp.code} ${resp.body}")
}

fun isNameOk(name: String, mimetype: String, emojiList: Map<String, String>): Result<Boolean> {
    // When a user pastes an image from the clipboard, it's named `image.png` . To avoid too many
    // accidentally wrongly named emoji, lets just not support it.
    if (name == "image") {
        return Result.failure(Exception("Sorry, for boring reasons we don't support managing the emoji `:image:`"))
    }

    // Make sure we can actually handle this file
    // Does "image/apng" work? What about "image/webp"?
    if (mimetype !in arrayListOf("image/png", "image/jpg", "image/jpeg", "image/gif")) {
        return Result.failure(Exception("That's a nice file you have, there.  Sadly, I don't know how to handle a file of type `$mimetype`. Try again with a jpg, png, or gif."))
    }

    if (!slackNameRegex.matches(name)) {
        return Result.failure(Exception("`:$name:` contains characters that Slack wont allow. Slack only allows letters, numbers, underscore, hyphen, and plus, please choose another name."))
    }

    val isReplacement = (name in emojiList)

    if (isReplacement && (emojiList[name].equals(BUILT_IN))) {
        return Result.failure(Exception("`:$name:` is a built-in emoji, and there is no way to override that. Sorry, please choose another name."))
    }

    return Result.success(true)
}
