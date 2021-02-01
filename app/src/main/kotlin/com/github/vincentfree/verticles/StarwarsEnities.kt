package com.github.vincentfree.verticles

import io.vertx.core.json.JsonObject

internal inline class Entity(val entity: List<String>)

internal data class StarWarsEntities(
    val residents: Entity,
    val films: Entity,
    val starships: Entity,
    val vehicles: Entity,
) {

    companion object {
        fun fromJsonObject(json: JsonObject): StarWarsEntities {
            val residents = json.getJsonArray("residents")?.let { it.list.toEntity() } ?: emptyEntity()
            val films = json.getJsonArray("films")?.let { it.list.toEntity() } ?: emptyEntity()
            val starships = json.getJsonArray("starships")?.let { it.list.toEntity() } ?: emptyEntity()
            val vehicles = json.getJsonArray("vehicles")?.let { it.list.toEntity() } ?: emptyEntity()
            return StarWarsEntities(residents, films, starships, vehicles)
        }
    }
}
private fun List<*>.toEntity(): Entity = Entity(this.mapNotNull { it as? String })
private fun emptyEntity(): Entity = Entity(emptyList())