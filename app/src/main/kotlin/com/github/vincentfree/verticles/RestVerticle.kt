package com.github.vincentfree.verticles

import io.vertx.core.Vertx
import io.vertx.core.http.HttpHeaders
import io.vertx.core.tracing.TracingPolicy
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger("RestVerticle")
class RestVerticle : CoroutineVerticle() {
    override suspend fun start() {
        val serverOptions = httpServerOptionsOf(
            tracingPolicy = TracingPolicy.ALWAYS
        )
        val httpServer = vertx.createHttpServer(serverOptions)
        httpServer.requestHandler(setupRouter(vertx))
        httpServer.listen(8080).onSuccess {
            logger.info("The server has started on port: ${httpServer.actualPort()}")
        }
    }

    private suspend fun setupRouter(vertx: Vertx): Router = coroutineScope {
        withContext(vertx.dispatcher()) {
            val router = Router.router(vertx)
            router.hello()
            router.helloWithName()
            router
        }
    }

    private fun Router.hello(): Route = get("/hello")
        .produces(JSON)
        .handler {
        it.response().run {
            putHeader(HttpHeaders.CONTENT_TYPE, JSON)
            end("hello world")
        }
    }

    private fun Router.helloWithName(): Route = get("/hello/:name")
        .produces(JSON)
        .handler {
            val name = it.pathParam("name") ?: "world"
        it.response().run {
            putHeader(HttpHeaders.CONTENT_TYPE, JSON)

            end("hello, $name!")
        }
    }

    companion object {
        const val JSON = "application/json"
        const val PLAIN = "text/plain"
    }
}