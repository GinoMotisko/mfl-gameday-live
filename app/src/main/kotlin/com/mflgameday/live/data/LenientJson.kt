package com.mflgameday.live.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Shared lenient JSON instance and extension helpers specifically designed for
 * MyFantasyLeague and ESPN's polymorphic wire formats (single-object vs array,
 * numbers encoded as strings, empty strings for nulls, etc.).
 */
object LenientJson {
  val parser: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
  }
}

fun JsonObject.str(key: String): String? =
  (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

fun JsonObject.strOrEmpty(key: String): String =
  (this[key] as? JsonPrimitive)?.contentOrNull?.trim() ?: ""

fun JsonObject.intOrNull(key: String): Int? =
  str(key)?.toIntOrNull() ?: str(key)?.toDoubleOrNull()?.toInt()

fun JsonObject.intOrZero(key: String): Int = intOrNull(key) ?: 0

fun JsonObject.doubleOrZero(key: String): Double =
  str(key)?.toDoubleOrNull() ?: 0.0

fun JsonObject.boolFromYesOrOne(key: String): Boolean {
  val v = str(key)?.lowercase() ?: return false
  return v == "1" || v == "yes" || v == "true"
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/**
 * MFL's XML-to-JSON exporter emits a JSON object when a list has 1 element,
 * a JSON array when it has 2+ elements, and omits/nulls it when empty.
 */
fun JsonObject.oneOrManyObjects(key: String): List<JsonObject> {
  return when (val el: JsonElement? = this[key]) {
    null, is JsonNull -> emptyList()
    is JsonObject -> listOf(el)
    is JsonArray -> el.mapNotNull { it as? JsonObject }
    else -> emptyList()
  }
}
