package com.target.slack

import com.target.liteforjdbc.Db
import com.target.liteforjdbc.getLocalDateTime
import com.target.liteforjdbc.getUUID
import com.target.liteforjdbc.propertiesToMap
import java.sql.ResultSet
import java.time.LocalDateTime
import java.util.UUID

data class EmojiFile(
    val sha1: String,
    var file: ByteArray,
    var contentType: String
) {
    companion object {
        fun rowMapper(resultSet: ResultSet): EmojiFile = with(resultSet) {
            EmojiFile(
                sha1 = getString("sha1"),
                file = getBytes("file"),
                contentType = getString("content_type")
            )
        }
    }
}

class EmojiFiles(private val db: Db) {

    fun upsert(emojiFile: EmojiFile): EmojiFile {
        return checkNotNull(
            db.executeQuery(
                sql = """
                    INSERT INTO emojifiles (sha1, file, content_type)
                      VALUES (:sha1, :file, :contentType)
                    ON CONFLICT (sha1) DO UPDATE
                      SET file = :file, content_type = :contentType
                    RETURNING *
                """.trimIndent(),
                args = emojiFile.propertiesToMap(),
                rowMapper = EmojiFile::rowMapper
            )
        ) { "Unexpected state: Insert didn't return a result." }
    }
    fun findFileBySha1(sha1: String): EmojiFile? =
        db.executeQuery(
            sql = "SELECT * FROM emojifiles WHERE sha1 = :sha1",
            args = mapOf("sha1" to sha1),
            rowMapper = EmojiFile::rowMapper
        )
}

data class Proposal(
    val id: UUID = UUID.randomUUID(),
    var created: LocalDateTime = LocalDateTime.now(),
    var state: String = Proposals.STATE_NEW,
    var action: String = Proposals.ACTION_ADD,
    var emoji: String,
    var fileId: String? = null, // SHA1 hash from emojifiles table
    var alias: Boolean = false,
    var cname: String? = null,
    var thread: String,
    var permalink: String? = null,
    var user: String,
    var previewFile: String? = null, // Slack File ID
    var previewUrl: String? = null,
) {

    companion object {

        fun rowMapper(resultSet: ResultSet): Proposal {
            return Proposal(
                id = checkNotNull(resultSet.getUUID("id")) { "Unexpected: Got a record without an id" },
                created = checkNotNull(resultSet.getLocalDateTime("created")) { "Unexpected: Got a record without a date" },
                state = resultSet.getString("state"),
                action = resultSet.getString("action"),
                emoji = resultSet.getString("emoji"),
                fileId = resultSet.getString("file"),
                alias = resultSet.getBoolean("alias"),
                cname = resultSet.getString("cname"),
                thread = resultSet.getString("thread"),
                permalink = resultSet.getString("permalink"),
                user = resultSet.getString("user"),
                previewFile = resultSet.getString("preview_file"),
                previewUrl = resultSet.getString("preview_url"),
            )
        }
    }
}

class Proposals(private val db: Db) {

    companion object {
        const val UPVOTE = "white_check_mark"
        const val DOWNVOTE = "x"
        const val FORCE = "large_green_circle"
        const val BLOCK = "no_entry_sign"
        const val WITHDRAW = "rewind"

        const val STATE_NEW = "new"
        const val STATE_ACCEPTED = "accepted"
        const val STATE_REJECTED = "rejected"
        const val STATE_FAILED = "failed"
        const val STATE_WITHDRAWN = "withdrawn"

        const val ACTION_ADD = "add"
        const val ACTION_REMOVE = "remove"
    }
    fun upsert(proposal: Proposal): Proposal {
        return db.withTransaction { tx ->
            val newProposal = checkNotNull(
                tx.executeQuery(
                    sql = """
                        INSERT INTO proposals (id, created, state, action, emoji, file, alias, cname, thread, permalink, "user", preview_file, preview_url)
                        VALUES (:id, :created, :state, :action, :emoji, :fileId, :alias, :cname, :thread, :permalink, :user, :previewFile, :previewUrl)
                        ON CONFLICT (id) DO UPDATE
                        SET created = :created, state = :state, action = :action, emoji = :emoji, file = :fileId, alias = :alias, cname = :cname, thread = :thread, permalink = :permalink, "user" = :user, preview_file = :previewFile, preview_url = :previewUrl
                        RETURNING *
                    """.trimIndent(),
                    args = proposal.propertiesToMap(),
                    rowMapper = Proposal::rowMapper
                )
            ) { "Unexpected state: Insert didn't return a result." }

            newProposal
        }
    }

    fun findByThread(thread: String): Proposal? =
        db.executeQuery(
            sql = "SELECT * FROM proposals WHERE thread = :thread",
            args = mapOf("thread" to thread),
            rowMapper = Proposal::rowMapper
        )

    fun findAllByState(state: String, limit: Int? = null): List<Proposal> {
        val limitSql = if (limit != null) {
            "LIMIT :limit"
        } else {
            ""
        }

        return db.findAll(
            sql = "SELECT * FROM proposals WHERE state = :state ORDER BY created DESC $limitSql",
            args = mapOf("state" to state, "limit" to limit),
            rowMapper = Proposal::rowMapper
        )
    }

    fun findProposalByID(id: UUID): Proposal? =
        db.executeQuery(
            sql = "SELECT * FROM proposals WHERE id = :id",
            args = mapOf("id" to id),
            rowMapper = Proposal::rowMapper
        )

    fun getRecentlyAdded(days: Long = 14, limit: Int = 32): List<Pair<Proposal, LocalDateTime>> {
        return db.findAll(
            sql = """
                SELECT a.date as added_date, p.*
                FROM auditlog as a, proposals as p
                WHERE
                    a.proposal = p.id AND
                    a.action = :action AND
                    a.date > :dateLimit
                ORDER BY a.date DESC
                LIMIT :limit
            """.trimIndent(),
            args = mapOf(
                "action" to AuditLog.ACTION_SYSTEM_UPLOAD,
                "limit" to limit,
                "dateLimit" to LocalDateTime.now().minusDays(days)
            ),
            rowMapper = { resultSet ->
                Pair(
                    Proposal.rowMapper(resultSet),
                    checkNotNull(resultSet.getLocalDateTime("added_date")) { "Unexpected: Got a record without an added_date" }
                )
            }
        )
    }

    fun getRecentErrors(days: Long = 14, limit: Int = 32): List<Pair<Proposal, AuditEntry>> {
        return db.findAll(
            sql = """
                SELECT a.id as a_id, a.date as a_date, a.actor_id as a_actor_id, a.action as a_action,
                       a.proposal as a_proposal, a.emoji as a_emoji, a.note as a_note, p.*
                FROM auditlog as a, proposals as p
                WHERE
                    a.proposal = p.id AND
                    a.action = :action AND
                    a.date > :dateLimit
                ORDER BY a.date DESC 
                LIMIT :limit
            """.trimIndent(),
            args = mapOf(
                "action" to AuditLog.ACTION_SYSTEM_FAIL,
                "limit" to limit,
                "dateLimit" to LocalDateTime.now().minusDays(days)
            ),
            rowMapper = { resultSet ->
                Pair(
                    Proposal.rowMapper(resultSet),
                    AuditEntry(
                        id = checkNotNull(resultSet.getUUID("a_id")) { "Unexpected: Got a record without an id" },
                        date = checkNotNull(resultSet.getLocalDateTime("a_date")) { "Unexpected: Got a record without a date" },
                        actorId = resultSet.getString("a_actor_id"),
                        action = resultSet.getString("a_action"),
                        proposalId = resultSet.getUUID("a_proposal"),
                        emoji = resultSet.getString("a_emoji"),
                        note = resultSet.getString("a_note"),
                    )
                )
            }
        )
    }
}

data class Emoji(
    val name: String,
    var file: String? = null, // SHA1 from emojifiles table
    var alias: Boolean,
    var cname: String? = null,
    var updated: LocalDateTime = LocalDateTime.now(),
    var proposalId: UUID,
) {
    companion object {
        fun rowMapper(resultSet: ResultSet): Emoji {
            return Emoji(
                name = resultSet.getString("name"),
                file = resultSet.getString("file"),
                alias = resultSet.getBoolean("alias"),
                cname = resultSet.getString("cname"),
                updated = checkNotNull(resultSet.getLocalDateTime("updated")) { "Unexpected: got a record without an updated date" },
                proposalId = checkNotNull(resultSet.getUUID("proposal")) { "Unexpected: got a record without a proposal ID" },
            )
        }
    }
}

class Emojis(private val db: Db) {

    fun upsert(emoji: Emoji): Emoji {
        return db.withTransaction { tx ->
            val newEmoji = checkNotNull(
                tx.executeQuery(
                    sql = """
                    INSERT INTO emojis (name, file, alias, cname, updated, proposal)
                    VALUES (:name, :file, :alias, :cname, :updated, :proposalId)
                    ON CONFLICT (name) DO UPDATE
                    SET file = :file, alias = :alias, cname = :cname, updated = :updated, proposal = :proposalId
                    RETURNING *
                    """.trimIndent(),
                    args = emoji.propertiesToMap(),
                    rowMapper = Emoji::rowMapper
                )
            ) { "Unexpected state: Insert didn't return a result." }

            newEmoji
        }
    }

    fun findEmojiByName(name: String): Emoji? =
        db.executeQuery(
            sql = "SELECT * FROM emojis WHERE name = :name",
            args = mapOf("name" to name),
            rowMapper = Emoji::rowMapper
        )

    fun removeEmojiByName(name: String) =
        db.executeUpdate(
            sql = "DELETE FROM emojis WHERE name = :name",
            args = mapOf("name" to name)
        )
}

data class AuditEntry(
    val id: UUID = UUID.randomUUID(),
    var date: LocalDateTime = LocalDateTime.now(),
    var actorId: String,
    var action: String,
    var proposalId: UUID? = null,
    var emoji: String? = null,
    var note: String? = null,
) {
    companion object {

        fun rowMapper(resultSet: ResultSet): AuditEntry {
            return AuditEntry(
                id = checkNotNull(resultSet.getUUID("id")) { "Unexpected: Got a record without an id" },
                date = checkNotNull(resultSet.getLocalDateTime("date")) { "Unexpected: Got a record without a date" },
                actorId = resultSet.getString("actor_id"),
                action = resultSet.getString("action"),
                proposalId = resultSet.getUUID("proposal"),
                emoji = resultSet.getString("emoji"),
                note = resultSet.getString("note"),
            )
        }
    }
}

class AuditLog(private val db: Db) {

    companion object {
        const val ACTION_PROPOSE_NEW = "propose:new"
        const val ACTION_PROPOSE_DEL = "propose:delete"
        const val ACTION_PROPOSE = "propose:%"
        const val ACTION_VOTE_UP = "vote:up"
        const val ACTION_VOTE_DN = "vote:down"
        const val ACTION_VOTE = "vote:%"
        const val ACTION_ADMIN_BLOCK = "admin:block"
        const val ACTION_ADMIN_UNBLOCK = "admin:unblock"
        const val ACTION_ADMIN_FORCE = "admin:force"
        const val ACTION_ADMIN_RESET = "admin:reset"
        const val ACTION_SYSTEM_UPLOAD = "system:upload"
        const val ACTION_SYSTEM_DELETE = "system:delete"
        const val ACTION_SYSTEM_REJECT = "system:reject"
        const val ACTION_SYSTEM_FAIL = "system:fail"
        const val ACTION_SYSTEM_WITHDRAW = "system:withdraw"
        const val ACTOR_ID_SYSTEM = "system"
    }

    fun insert(entry: AuditEntry): AuditEntry {
        return db.withTransaction { tx ->
            val newEntry = checkNotNull(
                tx.executeQuery(
                    sql = """
                    INSERT INTO auditlog (id, date, actor_id, action, proposal, emoji, note)
                    VALUES (:id, :date, :actorId, :action, :proposalId, :emoji, :note)
                    RETURNING *
                    """.trimIndent(),
                    args = entry.propertiesToMap(),
                    rowMapper = AuditEntry::rowMapper
                )
            ) { "Unexpected state: Insert didnt return a result." }

            newEntry
        }
    }

    fun findByProposal(proposal: UUID): List<AuditEntry> =
        db.findAll(
            sql = "SELECT * FROM auditlog WHERE proposal = :proposal",
            args = mapOf("proposal" to proposal),
            rowMapper = AuditEntry::rowMapper,
        )
}
