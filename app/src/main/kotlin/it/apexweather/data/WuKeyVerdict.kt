package it.apexweather.data

/**
 * What the app last observed about the reader's Weather Underground key.
 *
 * Three failures rather than one, because they need three different things from the reader: a
 * refused key wants re-typing, an exhausted quota wants waiting until the cap resets, and a
 * connection failure wants nothing at all. "Funktioniert nicht" would name none of them.
 *
 * Entering a key used to change nothing visible — the thirty-minute staleness rule means the app
 * does not refetch, so the screen went on reading the provincial station and a wrong key looked
 * exactly like a key that had simply not been used yet. Half an hour of a phone was spent on that
 * on 2026-09-23.
 */
enum class WuKeyVerdict {
    /** No key, or a key nothing has tried yet. Nothing is shown. */
    UNCHECKED,

    /** A check is in flight. */
    CHECKING,

    /** The last request carrying this key was answered. */
    GOOD,

    /** 401 or 403 — the key is wrong, or no longer a contributor's. */
    REFUSED,

    /** 429 — 1500 requests a day, 30 a minute. */
    OVER_QUOTA,

    /** The request never got an answer, or the answer was the server's own fault. Says nothing about the key. */
    OFFLINE,
}

object WuKeyVerdicts {
    fun of(httpCode: Int): WuKeyVerdict = when (httpCode) {
        401, 403 -> WuKeyVerdict.REFUSED
        429 -> WuKeyVerdict.OVER_QUOTA
        in 200..299 -> WuKeyVerdict.GOOD
        // Everything else — a 500, a 404 from a station that has gone — is not evidence about the
        // key, and a verdict that accused it would send the reader to re-type a good one.
        else -> WuKeyVerdict.OFFLINE
    }

    fun of(failure: Throwable): WuKeyVerdict = WuKeyVerdict.OFFLINE
}
