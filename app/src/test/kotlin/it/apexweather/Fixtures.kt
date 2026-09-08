package it.apexweather

import kotlinx.serialization.json.Json

object Fixtures {
    val json: Json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    fun read(name: String): String =
        checkNotNull(Fixtures::class.java.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .bufferedReader().readText()
}
