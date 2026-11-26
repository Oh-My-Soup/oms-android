package dev.oms.remote.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** Wire JSON settings (spec 3.3): tolerant decoding, absent optionals are omitted on encode. */
internal val WireJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = false
}

@OptIn(ExperimentalSerializationApi::class)
private val PrettyJson: Json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
}

internal fun encodeJson(element: JsonElement): String = WireJson.encodeToString(JsonElement.serializer(), element)

internal fun prettyJson(element: JsonElement): String = PrettyJson.encodeToString(JsonElement.serializer(), element)

/** Parses one JSON object, or null when [text] is not a JSON object. */
internal fun parseJsonObject(text: String): JsonObject? =
    runCatching { WireJson.parseToJsonElement(text) }.getOrNull() as? JsonObject

internal val EmptyJsonObject = JsonObject(emptyMap())

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

/** An integral JSON number (JS numbers may arrive as `3.0`). */
internal fun JsonObject.long(key: String): Long? {
    val primitive = (this[key] as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    primitive.longOrNull?.let { return it }
    val d = primitive.doubleOrNull ?: return null
    return if (d.isFinite() && d == Math.floor(d) && kotlin.math.abs(d) < 9.0E15) d.toLong() else null
}

internal fun JsonObject.int(key: String): Int? = long(key)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

internal val JsonElement.asObject: JsonObject? get() = this as? JsonObject
