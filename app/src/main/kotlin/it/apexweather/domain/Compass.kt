package it.apexweather.domain

/**
 * Where the wind is coming from, to the eighth of a circle.
 *
 * Eight points rather than sixteen because that is as much as the models agree about. The consensus
 * direction is a mean over eight to ten runs, and half of them disagreeing by twenty degrees is
 * ordinary in a valley; naming the result "west-north-west" would be reporting a precision the
 * inputs do not have. Eight points is also what a reader takes in without decoding — "aus NW" is a
 * side of the valley, "292°" is arithmetic.
 *
 * The direction is the meteorological one: where the wind comes *from*, which is what every upstream
 * here publishes and what "Nordwind" means.
 */
enum class CompassPoint {
    N, NE, E, SE, S, SW, W, NW;

    companion object {
        /** The point [degrees] falls in, each covering the 45° centred on its own bearing. */
        fun of(degrees: Int): CompassPoint {
            val normalised = ((degrees % 360) + 360) % 360
            return entries[(((normalised + 22.5) / 45).toInt()) % entries.size]
        }
    }
}
