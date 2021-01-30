package com.github.vincentfree.client

import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.client.HttpResponse
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.predicate.ResponsePredicate
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.kotlin.coroutines.await
import io.vertx.kotlin.ext.web.client.webClientOptionsOf

const val extURL = "swapi.dev"

fun WebClient.starWarsPeople(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/people/")
fun WebClient.starWarsVehicles(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/vehicles/")
fun WebClient.starWarsStarships(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/starships/")
fun WebClient.starWarsFilms(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/films/")
fun WebClient.starWarsPlanets(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/planets/")
fun WebClient.starWarsSpecies(): Future<HttpResponse<JsonObject>> = starWarsRequest("/api/species/")
fun WebClient.starWarsRequest(url: String): Future<HttpResponse<JsonObject>> = get(443, extURL, url)
    .ssl(true)
    .`as`(BodyCodec.jsonObject())
    .timeout(2500)
    .send()

fun WebClient.getAll(response: Future<HttpResponse<JsonObject>>): Future<JsonArray> = response.flatMap {
    getNextBatch(BatchResult(it.body().getString("next"), JsonArray())).map { batchResult ->
        batchResult.result
    }
}

private data class BatchResult(val url: String, val result: JsonArray)

private fun WebClient.getNextBatch(batchResult: BatchResult): Future<BatchResult> {
    val endpoint = batchResult.url.replace("http://", "").replace(extURL, "")
    return get(443, extURL, endpoint)
        .ssl(true)
        .`as`(BodyCodec.jsonObject())
        .timeout(2500)
        .send().flatMap {
            val json = it.body()
            json.getString("next")?.let { next ->
                getNextBatch(
                    BatchResult(
                        next,
                        batchResult.result.copy().addAll(it.body().getJsonArray("results"))
                    )
                )
            } ?: Future.succeededFuture(batchResult)
        }
}

suspend fun WebClient.starWarsPeopleAwait(): HttpResponse<JsonObject> = starWarsPeople().await()

fun WebClient.starWarsPerson(name: String): Future<HttpResponse<JsonObject>> = starWarsNamedRequest(
    name, "/api/people/"
)

fun WebClient.starWarsNamedRequest(name: String, url: String): Future<HttpResponse<JsonObject>> =
    get(443, extURL, url)
        .ssl(true)
        .addQueryParam("search", name)
        .`as`(BodyCodec.jsonObject())
        .expect(ResponsePredicate.JSON)
        .timeout(1000)
        .send()

suspend fun WebClient.starWarsPersonAwait(name: String): HttpResponse<JsonObject> = starWarsPerson(name).await()

val webClientOptions = webClientOptionsOf(
    defaultPort = 443,
    ssl = true,
    verifyHost = false,
)