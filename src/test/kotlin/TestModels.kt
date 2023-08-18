import com.target.liteforjdbc.Db
import com.target.liteforjdbc.DbConfig
import com.target.slack.AuditEntry
import com.target.slack.AuditLog
import com.target.slack.Emoji
import com.target.slack.EmojiFile
import com.target.slack.EmojiFiles
import com.target.slack.Emojis
import com.target.slack.Proposal
import com.target.slack.Proposals
import com.target.slack.hashString
import org.junit.Test

class TestModels {

    @Test
    fun testModels() {
        // Database.connect("jdbc:h2:mem:test", driver = "org.h2.Driver", user = "root", password = "")
        val db = Db(
            DbConfig(
                databaseName = "emojimanagerdocker",
                username = "emojimanager_app",
                password = "emojimanager_app",
            )
        )
        // TODO: Create tables
        // SchemaUtils.create(EmojiFiles, Proposals, Emojis, AuditLog)

        var emojis = Emojis(db)
        var emojiFiles = EmojiFiles(db)
        var proposals = Proposals(db)
        var auditLog = AuditLog(db)

        val rawFile = "testrawfilething".encodeToByteArray()
        val rawSha1 = hashString("SHA-1", rawFile)
        var propFile = emojiFiles.findFileBySha1(rawSha1)

        if (propFile == null) {
            propFile = emojiFiles.upsert(
                EmojiFile(
                    sha1 = rawSha1,
                    file = rawFile,
                    contentType = "image/png",
                )
            )
        }

        var propEmoji = proposals.upsert(
            Proposal(
                emoji = "test",
                thread = "123456",
                fileId = propFile.sha1,
                user = "W1234",
            )
        )

        auditLog.insert(
            AuditEntry(
                actorId = "W12345",
                action = AuditLog.ACTION_PROPOSE_NEW,
                proposalId = propEmoji.id,
            )
        )

        auditLog.insert(
            AuditEntry(
                actorId = "W12345",
                action = AuditLog.ACTION_VOTE_UP,
                proposalId = propEmoji.id,
            )
        )

        var emoji = emojis.findEmojiByName("test")
        if (emoji == null) {
            emoji = emojis.upsert(
                Emoji(
                    name = propEmoji.emoji,
                    alias = propEmoji.alias,
                    file = propEmoji.fileId,
                    proposalId = propEmoji.id,
                )
            )
        }

        auditLog.insert(
            AuditEntry(
                actorId = AuditLog.ACTOR_ID_SYSTEM,
                action = AuditLog.ACTION_SYSTEM_UPLOAD,
                proposalId = propEmoji.id,
                emoji = emoji.name
            )
        )
    }
}
