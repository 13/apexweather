package it.apexweather.data.remote

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

internal fun JsonObject.doubles(key: String): List<Double?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.doubleOrNull } ?: emptyList()

internal fun JsonObject.ints(key: String): List<Int?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.intOrNull } ?: emptyList()

internal fun JsonObject.strings(key: String): List<String?> =
    this[key]?.jsonArray?.map { it.jsonPrimitive.contentOrNull } ?: emptyList()

/** "2026-09-08T06:00" (no offset) interpreted in [zone]. */
internal fun parseLocal(s: String, zone: ZoneId): Instant = LocalDateTime.parse(s).atZone(zone).toInstant()

/** "2026-09-08T06:00:00+02:00" or "2026-09-08T16:00+00:00". */
internal fun parseOffset(s: String): Instant = OffsetDateTime.parse(s).toInstant()

/** SIAG station strings: "31.1" or "--". */
internal fun String?.siagDouble(): Double? = this?.trim()?.takeIf { it != "--" && it.isNotEmpty() }?.toDoubleOrNull()
