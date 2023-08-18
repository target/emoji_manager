package com.target.slack

import com.slack.api.bolt.App
import com.slack.api.bolt.http4k.Http4kSlackApp
import com.target.liteforjdbc.Db
import org.http4k.core.MemoryBody
import org.http4k.core.Method
import org.http4k.core.Request
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.routing.bind
import org.http4k.routing.path
import org.http4k.routing.routes

class Http4KEmojiManagerApp(val config: Config, app: App, path: String, private val db: Db) : Http4kSlackApp(app, path) {

    private val emojiFiles = EmojiFiles(db)

    private val routes = routes(
        "/health" bind Method.GET to { Response(Status.OK).body("OK") },
        config.server.urlPath + "_images_{id}" bind Method.GET to { req -> getImage(req.path("id")!!) },
        config.server.urlPath + "_images/{id}" bind Method.GET to { req -> getImage(req.path("id")!!) },
        config.server.urlPath + "_events" bind Method.GET to { req -> super.invoke(req) },
        config.server.urlPath + "_events" bind Method.POST to { req -> super.invoke(req) }
    )

    override fun invoke(req: Request): Response {
        return routes.invoke(req)
    }

    private fun getImage(sha1: String): Response {
        val file = checkNotNull(emojiFiles.findFileBySha1(sha1)) {
            return Response(Status.NOT_FOUND)
        }

        return Response(Status.OK)
            .header("Content-Type", file.contentType)
            .body(MemoryBody(payload = file.file))
    }
}
