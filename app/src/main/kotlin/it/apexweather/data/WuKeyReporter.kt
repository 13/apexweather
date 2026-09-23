package it.apexweather.data

/**
 * Where a verdict about the Weather Underground key is written down.
 *
 * An interface for the same reason [WuKeySource] is one: [WeatherRepository] knows nothing about
 * settings and is not going to start. It observes what the key did and hands that observation over;
 * somewhere else decides that "somewhere else" is DataStore.
 */
fun interface WuKeyReporter {
    suspend fun report(verdict: WuKeyVerdict)
}
