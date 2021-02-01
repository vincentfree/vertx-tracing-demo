package com.github.vincentfree.verticles

import com.github.vincentfree.client.getAll
import com.github.vincentfree.client.starWarsPeople
import com.github.vincentfree.client.starWarsPlanets
import com.github.vincentfree.client.starWarsRequest
import com.github.vincentfree.util.Addresses
import com.github.vincentfree.util.CallType
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Promise
import io.vertx.core.Vertx
import io.vertx.core.eventbus.EventBus
import io.vertx.core.http.HttpHeaders
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Route
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.handler.ResponseTimeHandler
import io.vertx.ext.web.handler.TimeoutHandler
import io.vertx.kotlin.core.json.jsonObjectOf
import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.future.asDeferred
import org.apache.logging.log4j.LogManager

private val logger = LogManager.getLogger("RouterInitializers")
private const val JSON = "application/json"
private const val PLAIN = "text/plain"

class RouterInitializers(private val client: WebClient) {
    fun Router.hello(): Route = get("/hello")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            it.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)
                end("hello world")
            }
        }

    fun Router.helloWithName(): Route = get("/hello/:name")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            val name = it.pathParam("name") ?: "world"
            it.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)

                end("hello, $name!")
            }
        }

    fun Router.talkedAbout(): Route = get("/sw/:name")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler {
            it.pathParam("name")?.let { name ->
                TODO("get character from cache or DB")
            } ?: it.response().end("Character name was empty")

        }

    fun Router.starWarsPersons(): Route = get("/sw/persons")
        .produces(JSON)
        .handler(TimeoutHandler.create(3000))
        .handler(ResponseTimeHandler.create())
        .handler { ctx ->
            cacheAndCallForAllApi(ctx) { client.starWarsPeople() }
        }

    suspend fun Router.starWarsPlanets(): Route {
        val promise = Promise.promise<Route>()
        coroutineScope {
            promise.complete(get("/sw/planets")
                .produces(JSON)
                .handler(TimeoutHandler.create(3000))
                .handler(ResponseTimeHandler.create())
                .handler { ctx ->
                    cacheAndCallForAllApi(ctx) { client.starWarsPlanets() }
                })
        }
        return promise.future().await()
    }

    fun Router.starWarsPerson(): Route {
        return get("/sw/persons/:name")
            .handler(TimeoutHandler.create(3000))
            .handler(ResponseTimeHandler.create())
            .handler { ctx ->
                ctx.pathParam("name")?.let { name ->
                    ctx.vertx().eventBus()
                        .getResult(name)
                        .onComplete { async ->
                            async.handleAsyncResult(ctx)
                        }
                }
            }
    }

    private fun AsyncResult<JsonArray?>.handleAsyncResult(ctx: RoutingContext) {
        if (succeeded()) {
            result()?.let { json ->
                ctx.response().run {
                    putHeader(HttpHeaders.CONTENT_TYPE, JSON)
                    end(json.encode())
                }
            } ?: ctx.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)
                statusCode = 204
                end()
            }
        } else {
            ctx.response().run {
                putHeader(HttpHeaders.CONTENT_TYPE, PLAIN)
                statusCode = 500
                end(cause().message)
            }
        }
    }


    private fun cacheAndCallForAllApi(ctx: RoutingContext, action: () -> Future<HttpResponse<JsonObject>>) {
        val bus = ctx.vertx().eventBus()
        action()
            .flatMap { json ->
                with(bus) {
                    storeResults(json.body())
                    cachedOrCall(json)
                }
            }
            .map { pair ->
                val array = pair.first
                val entities = extractEntities(array)
                bus.request<JsonArray>(Addresses.byURL, entities)
                pair
            }
            .onSuccess { pair ->
                bus.storeResults(pair.first)
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

    private fun EventBus.cachedOrCall(json: HttpResponse<JsonObject>): Future<Pair<JsonArray, CallType>> {
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


    private fun EventBus.storeResults(json: JsonObject) = storeResults(json.getJsonArray("results"))

    private fun EventBus.storeResults(array: JsonArray) {
        for (i in 0 until array.size()) {
            publish(Addresses.cacheRecord, array.getJsonObject(i))
        }
    }

    private fun EventBus.getResult(query: String): Future<JsonArray?> {
        return request<JsonArray>(Addresses.requestRecord, query).map { it.body() }
    }

    private fun EventBus.getAllResults(): Future<JsonArray> {
        val promise = Promise.promise<JsonArray>()
        request<JsonArray>(Addresses.requestAllRecords, "") { asyncResult ->
            if (asyncResult.succeeded()) {
                promise.complete(asyncResult.result().body())
            } else {
                promise.fail(asyncResult.cause())
            }
        }
        return promise.future()
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun extractEntities(jsonArray: JsonArray): JsonArray {
        val array = jsonArray.copy()
        for (i in 0 until array.size()) {
            array.getJsonObject(i)?.let { json ->
                val entities = StarWarsEntities.fromJsonObject(json)
                //TODO fix references here!! entities.resolveAll() or something
                //array.add(i, json)
            } ?: continue
            // throw IllegalArgumentException("JsonObject should not be null")
        }
        return array
    }

    internal suspend fun EventBus.transformEntity(entity: Entity): List<JsonObject> =
        if (entity.entity.isNotEmpty()) {
            entity.entity.asFlow()
                .map { url: String ->
                    val msg = request<JsonObject>(Addresses.byURL, url).await()
                    if (msg.headers()["status"] == "success") {
                        msg.body()
                    } else {
                        client.starWarsRequest(url)
                            .map { response -> response.body() }
                            .await()
                    }
                }
                .fold(mutableListOf<JsonObject>()) { acc, value ->
                    acc.apply { add(value) }
                }
                .toList()
        } else emptyList()

    private suspend fun StarWarsEntities.replaceValues(json: JsonObject, bus: EventBus): JsonObject {
            val asyncResidents = bus.transformEntity(this.residents)
            val asyncFilms = bus.transformEntity(this.films)
            val asyncStarShips = bus.transformEntity(this.starships)
            val asyncVehicles = bus.transformEntity(this.vehicles)

            val result = json.copy()
            asyncResidents.replaceValue(result, "residents")
            asyncFilms.replaceValue(result, "films")
            asyncStarShips.replaceValue(result, "starships")
            asyncVehicles.replaceValue(result, "vehicles")
            return result

    }

    private suspend fun List<JsonObject>.replaceValue(source: JsonObject, listName: String): JsonArray {
        return source.getJsonArray(listName)
            .clear()
            .addAll(JsonArray(this))
    }

//    private fun Entity.mapEntityToJson(): JsonArray {
//        entity[0]
//    }
}