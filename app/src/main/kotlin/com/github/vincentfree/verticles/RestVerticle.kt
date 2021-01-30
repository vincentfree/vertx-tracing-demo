package com.github.vincentfree.verticles

import com.github.vincentfree.client.*
import com.github.vincentfree.util.Addresses
import com.github.vincentfree.util.CallType
import com.github.vincentfree.util.ConsoleColors
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.Message
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.tracing.TracingPolicy
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.ext.web.handler.TimeoutHandler
import io.vertx.kotlin.core.http.httpServerOptionsOf
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.awaitResult
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asDeferred
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
        httpServer.requestHandler(setupRouter(vertx))
        httpServer.listen(8080).onSuccess {
            logger.info(
                "The server has started on port: ${ConsoleColors.BLUE}${httpServer.actualPort()}${ConsoleColors.RESET}"
            )
        }.onFailure {
            logger.error("Failed to start, cause: ${it.message}")
        }
    }

    private suspend fun setupRouter(vertx: Vertx): Router = coroutineScope {
        withContext(vertx.dispatcher()) {
            val router = Router.router(vertx)
            router.apply {
                hello()
                helloWithName()
                starWarsPersons()
                starWarsPerson()
                starWarsPlanets()
            }
        }
    }

    private fun Router.hello(): Route = get("/hello")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            it.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, JSON)
                end("hello world")
            }
        }

    private fun Router.helloWithName(): Route = get("/hello/:name")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            val name = it.pathParam("name") ?: "world"
            it.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, JSON)

                end("hello, $name!")
            }
        }

    private fun Router.talkedAbout(): Route = get("/sw/:name")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            it.pathParam("name")?.let { name ->
                TODO("get character from cache or DB")
            } ?: it.response().end("Character name was empty")

        }

    private fun Router.starWarsPersons(): Route = get("/sw/persons")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler(ResponseTimeHandler.create())
        .handler { ctx ->
            cacheAndCallForAllApi(ctx) { client.starWarsPeople() }
        }

    private fun Router.starWarsPlanets(): Route = get("/sw/planets")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler(ResponseTimeHandler.create())
        .handler { ctx ->
            cacheAndCallForAllApi(ctx) { client.starWarsPlanets() }
        }

    private fun cacheAndCallForAllApi(ctx: RoutingContext, action: () -> Future<HttpResponse<JsonObject>>) {
        launch(vertx.dispatcher()) {
            action()
                .flatMap { json ->
                    storeResults(json.body())
                    cachedOrCall(json)
                }
                .map { pair ->
                    val array = pair.first
                    val entities = extractEntities(array)
                    bus.request<JsonArray>(Addresses.byURL, JsonArray())
                }
                .onSuccess { pair ->
                    storeResults(pair.first)
                    ctx.response().run {
                        putHeader(HttpHeaders.CONTENT_TYPE, JSON)
                        putHeader("x-content-source", pair.second.name)
                        end(pair.first.encode())
                    }
                }
                .onFailure { err ->
                    logger.error("Failed to call swapi, msg: ${err.message}", err)
                    ctx.response().run {
                        statusCode = 500
                        putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)
                        end(err.message)
                    }
                }
        }
    }

    private fun cachedOrCall(json: HttpResponse<JsonObject>): Future<Pair<JsonArray, CallType>> {
        infix fun Int.withMargin(subtract: Int) = this - subtract
        val count = json.body().getInteger("count")
        val cacheArray = getAllResults()
        return cacheArray.flatMap { array ->
            if (array.size() < count withMargin 8) {
                client.getAll(Future.succeededFuture(json)).map { it to CallType.CALL }
            } else {
                Future.succeededFuture(array).map { it to CallType.CACHE }
            }
        }
    }

    private fun Router.starWarsPerson(): Route = get("/sw/persons/:name")
        .handler(TimeoutHandler.create(3000))
        .handler(ResponseTimeHandler.create())
        .handler { ctx ->
            ctx.pathParam("name")?.let {
                launch(vertx.dispatcher()) {
                    getResult(it)?.let { json ->
                        ctx.response().run {
                            putHeader(HttpHeaders.CONTENT_TYPE, JSON)
                            end(json.encode())
                        }
                    } ?: ctx.response().run {
                        putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)
                        statusCode = 204
                        end()
                    }
                }
            }
        }

    private fun storeResults(json: JsonObject) = storeResults(json.getJsonArray("results"))

    private fun storeResults(array: JsonArray) {
        for (i in 0 until array.size()) {
            bus.publish(Addresses.cacheRecord, array.getJsonObject(i))
        }
    }

    private suspend fun getResult(query: String): JsonArray? {
        val msg = awaitResult<Message<JsonArray?>> { bus.request(Addresses.requestRecord, query, it) }
        return msg.body()
    }

    private fun getAllResults(): Future<JsonArray> {
        val promise = Promise.promise<JsonArray>()
        bus.request<JsonArray>(Addresses.requestAllRecords, "") { asyncResult ->
            if (asyncResult.succeeded()) {
                promise.complete(asyncResult.result().body())
            } else {
                promise.fail(asyncResult.cause())
            }
        }
        return promise.future()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private suspend fun extractEntities(jsonArray: JsonArray): JsonArray {
        val array = jsonArray.copy()
        for (i in 0 until array.size()) {
            val result = array.getJsonObject(i)?.let { json ->
                val entities = StarWarsEntities.fromJsonObject(json)


            } ?: continue
            array.add(i, result)
            // throw IllegalArgumentException("JsonObject should not be null")
        }
        return array
    }

    private fun JsonArray.mapEntityToJson(entityType: EntityType): JsonArray {
        val array = copy()
        when(entityType) {
            is EntityType.ResidentEntity -> array.
            is EntityType.FilmEntity -> {}
            is EntityType.StarShipEntity -> {}
            is EntityType.VehicleEntity -> {}
        }
    }


    companion object {
        const val JSON = "application/json"
        const val PLAIN = "text/plain"
    }
}

internal inline class Entity(val entity: List<String>)
internal sealed class EntityType() {
    data class ResidentEntity(val entity: Entity) : EntityType()
    data class FilmEntity(val entity: Entity) : EntityType()
    data class StarShipEntity(val entity: Entity) : EntityType()
    data class VehicleEntity(val entity: Entity) : EntityType()
}

internal data class StarWarsEntities(
    val residents: Entity,
    val films: Entity,
    val starships: Entity,
    val vehicles: Entity,
) {
    suspend fun replaceValues(json: JsonObject): JsonArray {
        val asyncResidents = transformEntity(residents)
        val asyncFilms = transformEntity(films)
        val asyncStarShips = transformEntity(starships)
        val asyncVehicles = transformEntity(vehicles)

        asyncResidents.replaceValue(json, "residents")
        asyncResidents.replaceValue(json, "residents")
    }

    private suspend fun List<Deferred<JsonObject>>.replaceValue(source: JsonObject, listName: String): JsonArray {
        return source.getJsonArray(listName)
            .clear()
            .addAll(JsonArray(this.awaitAll()))
    }

    companion object {
        fun fromJsonObject(json: JsonObject): StarWarsEntities {
            val residents = json.getJsonArray("residents")?.let { it.list.toEntity() } ?: emptyEntity()
            val films = json.getJsonArray("films")?.let { it.list.toEntity() } ?: emptyEntity()
            val starships = json.getJsonArray("starships")?.let { it.list.toEntity() } ?: emptyEntity()
            val vehicles = json.getJsonArray("vehicles")?.let { it.list.toEntity() } ?: emptyEntity()
            return StarWarsEntities(residents, films, starships, vehicles)
        }

        private suspend fun transformEntity(entity: Entity): List<Deferred<JsonObject>> =
            if (entity.entity.isNotEmpty()) {
                entity.entity.asFlow()
                    .map { url: String ->
                        client.starWarsRequest(url)
                            .map { response -> response.body() }
                            .toCompletionStage()
                            .asDeferred()
                    }
                    .fold(mutableListOf<Deferred<JsonObject>>()) { acc, value ->
                        acc.apply { add(value) }
                    }
                    .toList()
            } else emptyList()

    }
}

private fun List<*>.toStringList(): List<String> = this.mapNotNull { it as? String }
private fun List<*>.toEntity(): Entity = Entity(this.mapNotNull { it as? String })
private fun emptyEntity(): Entity = Entity(emptyList())