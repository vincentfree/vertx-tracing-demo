package com.github.vincentfree.verticles

import com.github.vincentfree.client.starWarsRequest
import com.github.vincentfree.client.webClientOptions
import com.github.vincentfree.util.ConsoleColors
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.tracing.TracingPolicy
import io.vertx.ext.web.Router
import io.vertx.ext.web.client.WebClient
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.withContext
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger("RestVerticle")

@OptIn(ExperimentalStdlibApi::class)
class RestVerticle : CoroutineVerticle() {
    private val client by lazy { WebClient.create(vertx, webClientOptions) }
    private val bus by lazy { vertx.eventBus() }

    override suspend fun start() {
        val serverOptions = httpServerOptionsOf(
            tracingPolicy = TracingPolicy.ALWAYS
        )
        val httpServer = vertx.createHttpServer(serverOptions)
        val initializers = RouterInitializers(client)
        httpServer.requestHandler(setupRouter(vertx, initializers))
        httpServer.listen(8080).onSuccess {
            logger.info(
                "The server has started on port: ${ConsoleColors.BLUE}${httpServer.actualPort()}${ConsoleColors.RESET}"
            )
        }.onFailure {
            logger.error("Failed to start, cause: ${it.message}")
        }
    }

    private suspend fun setupRouter(vertx: Vertx, initializers: RouterInitializers): Router = coroutineScope {
        withContext(vertx.dispatcher()) {
            val router = Router.router(vertx)
            router.apply {
                with(initializers) {
                    hello()
                    helloWithName()
                    starWarsPersons()
                    starWarsPerson()
                    starWarsPlanets()
                }
            }
        }
    }


    companion object {
        const val JSON = "application/json"
        const val PLAIN = "text/plain"
    }
}

private fun List<*>.toStringList(): List<String> = this.mapNotNull { it as? String }