package it.apexweather.data

/**
 * Where the Weather Underground key comes from, asked at the moment it is needed.
 *
 * A named interface rather than a bare `suspend () -> String?` for two reasons. A suspend function
 * type erases to `Function1<Continuation<T>, Any?>`, and Dagger cannot reliably match the wildcards
 * Kotlin puts on it — it asks for `@JvmSuppressWildcards` on both sides and the two still have to
 * agree. And a named type says at every injection site what is being injected, where a raw function
 * type says only that something returns a string eventually.
 *
 * It is a *function* and not a `SettingsRepository` on purpose: [WeatherRepository] stays ignorant
 * of settings, which is what lets the worker, the widget and the resume hook all call `refresh`
 * without agreeing about preferences first — the same shape
 * [it.apexweather.ui.ForegroundRefreshLoop] takes its inputs in, and for the same reason. A test
 * hands it a lambda and needs no DataStore.
 *
 * Null means the reader has given no key, and the amateur path is off.
 */
fun interface WuKeySource {
    suspend fun key(): String?
}
