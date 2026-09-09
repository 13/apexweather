package it.apexweather.update

/**
 * A release version as three numbers, so "0.10.0" sorts above "0.9.0" rather than below it.
 *
 * Version *names* are compared, never version codes. The release workflow derives the code from
 * the name in awk (`major * 10000 + minor * 100 + patch`); recomputing that rule here would put
 * the same arithmetic in a second language, where the two can drift apart silently.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int =
        compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })

    override fun toString(): String = "$major.$minor.$patch"

    companion object {
        /**
         * Reads "0.2.0" and the tag form "v0.2.0". A missing trailing part counts as zero, so
         * "1.2" is 1.2.0. Anything else, including a pre-release suffix such as "1.0.0-rc1",
         * returns null: the caller must report "cannot tell", never "up to date".
         */
        fun parse(raw: String?): AppVersion? {
            val text = raw?.trim()?.removePrefix("v").orEmpty()
            if (text.isEmpty()) return null
            val parts = text.split('.')
            if (parts.size > 3) return null
            val numbers = parts.map { part -> part.toIntOrNull()?.takeIf { it >= 0 } ?: return null }
            return AppVersion(numbers[0], numbers.getOrElse(1) { 0 }, numbers.getOrElse(2) { 0 })
        }
    }
}
