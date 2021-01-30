package com.github.vincentfree.verticles

import com.github.benmanes.caffeine.cache.Caffeine
import com.github.vincentfree.util.Addresses
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.kotlin.coroutines.CoroutineVerticle
import io.vertx.kotlin.coroutines.dispatcher
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.future.asDeferred
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.apache.logging.log4j.LogManager
import java.util.concurrent.TimeUnit

private val logger = LogManager.getLogger("CacheVerticle")

class CacheVerticle : CoroutineVerticle() {
    private val bus by lazy { vertx.eventBus() }
    override suspend fun start() {
        cacheRecord()
        getRecord()
        getAllRecords()
        super.start()
    }

    override suspend fun stop() {
        super.stop()
    }

    private fun cacheRecord() {
        bus.consumer<JsonObject>(Addresses.cacheRecord).handler { msg ->
            val json = msg.body()
            cache.put(
                json.getString("name").toLowerCase(), Future.succeededFuture(json)
                    .toCompletionStage()
                    .toCompletableFuture()
            )
            cache.put(
                json.getString("url").toLowerCase(), Future.succeededFuture(json)
                    .toCompletionStage()
                    .toCompletableFuture()
            )

        }
    }

    private fun getRecord() {
        bus.consumer<String>(Addresses.requestRecord).handler { msg ->
            val str = msg.body().toLowerCase()
            launch(vertx.dispatcher()) {
                val res = cache.asMap().asSequence().asFlow().filter { it.key.contains(str) }
                    .map { it.value.await() }
                    .fold(JsonArray()) { acc, value -> acc.add(value) }
                msg.reply(res)
            }
        }
    }

    private fun getAllRecords() {
        bus.consumer<Unit>(Addresses.requestAllRecords).handler { msg ->
            launch(vertx.dispatcher()) {
                val array = cache.asMap().entries.fold(JsonArray()) { acc, entry ->
                    acc.add(entry.value.await())
                }
                msg.reply(array)
            }

        }
    }

    private fun getByUrl() {
        bus.consumer<JsonArray>(Addresses.byURL).handler { msg ->
            val array = msg.body()
            launch(vertx.dispatcher()) {
                val res = cache.asMap().asSequence().asFlow()
                    .map { it.value.await() }
                    .filter { array.contains(it.getString("url"))  }
                    .fold(JsonArray()) { acc, value -> acc.add(value) }
                msg.reply(res)
            }
        }
    }

    companion object {
        val cache = Caffeine.newBuilder().apply {
            maximumSize(1000)
            expireAfterAccess(10, TimeUnit.MINUTES)
        }.buildAsync<String, JsonObject>()
    }
}