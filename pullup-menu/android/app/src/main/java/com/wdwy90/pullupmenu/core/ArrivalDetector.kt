package com.wdwy90.pullupmenu.core

import kotlin.math.min

/**
 * Decides when the car has "pulled up" somewhere: speed stays below [stopSpeedMps] for [dwellMs],
 * and the last lookup hasn't already answered for this spot. Pure logic so it can be unit tested
 * without Android.
 *
 * What a lookup answers for depends on how it ended ([found], [nothingFound], [failed]):
 * - nothing nearby, or still running: stops inside the circle it searched ([searchRadiusM]). A stop
 *   outside it is looked up, so a red light or a parking-lot stop just before the lane doesn't hide
 *   the lane;
 * - a place found: stops inside that circle that are no farther from the place (the line creeping
 *   toward the window). Pulling away from it, into the drive-thru next door say, is looked up again;
 * - failed (no internet, say): nothing. While the car stays stopped it's tried again after each of
 *   [retryDelaysMs]; then this spot counts as answered.
 *
 * A fix less accurate than [maxAccuracyM] can't tell neighbouring places apart, so the lookup waits
 * for a better one; after [impreciseWaitMs] stopped it runs anyway, over a wider circle ([radiusM]).
 */
class ArrivalDetector(
    // Drive-thru lines creep forward a car length at a time, so "stopped" means
    // under ~6.7 mph rather than fully still.
    private val stopSpeedMps: Double = 3.0,
    private val dwellMs: Long = 20_000,           // in line/stopped for 20 s
    /** The app's search radius (Prefs.radiusMeters). */
    private val searchRadiusM: Double = 45.0,
    private val maxAccuracyM: Double = 50.0,
    private val impreciseWaitMs: Long = 60_000,
    private val maxRadiusM: Double = 150.0,
    private val retryDelaysMs: List<Long> = listOf(15_000, 30_000, 60_000),
    /** GPS wander while stopped: a stop this much farther from a found place still counts as at it. */
    private val samePlaceSlackM: Double = 15.0,
) {
    data class Sample(
        val lat: Double,
        val lng: Double,
        val timeMs: Long,
        val speedMps: Double?,
        /** Horizontal accuracy in meters, when the fix has one. */
        val accuracyM: Double? = null,
    )

    private enum class Outcome { PENDING, FOUND, NOTHING, FAILED }

    private class Lookup(val id: Int, val lat: Double, val lng: Double) {
        var outcome = Outcome.PENDING
        var placeLat = 0.0
        var placeLng = 0.0
        var retryAtMs = 0L
    }

    private var prev: Sample? = null
    private var stoppedSince: Long? = null
    private var last: Lookup? = null
    private var nextId = 1
    /** Failed lookups since the car last drove. */
    private var failures = 0

    /** Id of the lookup started last ([onSample] or [markLookedUp]), for [found] and the others. */
    val lookupId: Int get() = last?.id ?: 0

    /** Radius for the lookup [onSample] just asked for: wider when the fix was poor. */
    var radiusM: Double = searchRadiusM
        private set

    /** Returns true when a restaurant lookup should be run for this sample's position (over [radiusM]). */
    fun onSample(s: Sample): Boolean {
        val speed = s.speedMps ?: prev?.let { p ->
            val dt = (s.timeMs - p.timeMs) / 1000.0
            if (dt > 0) Geo.distanceMeters(p.lat, p.lng, s.lat, s.lng) / dt else null
        } ?: 0.0
        prev = s

        if (speed > stopSpeedMps) {
            stoppedSince = null
            failures = 0
            return false
        }
        val since = stoppedSince ?: s.timeMs.also { stoppedSince = it }
        val stoppedFor = s.timeMs - since
        if (stoppedFor < dwellMs) return false

        val l = last
        if (l != null && (answers(l, s.lat, s.lng) || waitingToRetry(l, s))) return false

        val accuracy = s.accuracyM
        val imprecise = accuracy != null && accuracy > maxAccuracyM
        if (imprecise && stoppedFor < impreciseWaitMs) return false
        radiusM = if (accuracy != null && imprecise) min(searchRadiusM + accuracy, maxRadiusM) else searchRadiusM

        start(s.lat, s.lng)
        // At most one lookup per dwell: the next one needs another dwellMs stopped.
        stoppedSince = s.timeMs
        return true
    }

    /**
     * Record a lookup made elsewhere (manual check, car app opening) so auto-detect treats it like its
     * own. Returns its id for [found] and the others.
     */
    fun markLookedUp(lat: Double, lng: Double): Int {
        start(lat, lng)
        return lookupId
    }

    /** True if the last lookup already answered for this spot (see the class comment). */
    fun wasLookedUpNear(lat: Double, lng: Double): Boolean = last?.let { answers(it, lat, lng) } ?: false

    /** Lookup [id] found a place at [placeLat], [placeLng] (the one now on screen). */
    fun found(id: Int, placeLat: Double, placeLng: Double) {
        val l = current(id) ?: return
        l.outcome = Outcome.FOUND
        l.placeLat = placeLat
        l.placeLng = placeLng
        failures = 0
    }

    /** Lookup [id] found no restaurant. */
    fun nothingFound(id: Int) {
        val l = current(id) ?: return
        l.outcome = Outcome.NOTHING
        failures = 0
    }

    /**
     * Lookup [id] got no answer at [nowMs] (same clock as the samples). When [retryable] (no internet,
     * a timeout, Google busy) this spot is tried again while the car stays stopped.
     */
    fun failed(id: Int, retryable: Boolean, nowMs: Long) {
        val l = current(id) ?: return
        failures++
        if (!retryable || failures > retryDelaysMs.size) {
            // Trying again here won't help (a bad key, say), or has been tried enough.
            l.outcome = Outcome.NOTHING
        } else {
            l.outcome = Outcome.FAILED
            l.retryAtMs = nowMs + retryDelaysMs[failures - 1]
        }
    }

    /** Forget the last lookup, e.g. when it turned out to be a red light: it says nothing about the next stop. */
    fun forget() {
        last = null
        failures = 0
    }

    /** Start over: forget the last lookup and any stop in progress. */
    fun reset() {
        stoppedSince = null
        forget()
    }

    private fun start(lat: Double, lng: Double) {
        last = Lookup(nextId++, lat, lng)
    }

    private fun current(id: Int): Lookup? = last?.takeIf { it.id == id }

    private fun answers(l: Lookup, lat: Double, lng: Double): Boolean {
        if (Geo.distanceMeters(l.lat, l.lng, lat, lng) >= searchRadiusM) return false
        return when (l.outcome) {
            Outcome.PENDING, Outcome.NOTHING -> true
            Outcome.FOUND -> Geo.distanceMeters(lat, lng, l.placeLat, l.placeLng) <=
                Geo.distanceMeters(l.lat, l.lng, l.placeLat, l.placeLng) + samePlaceSlackM
            Outcome.FAILED -> false
        }
    }

    private fun waitingToRetry(l: Lookup, s: Sample): Boolean =
        l.outcome == Outcome.FAILED && s.timeMs < l.retryAtMs &&
            Geo.distanceMeters(l.lat, l.lng, s.lat, s.lng) < searchRadiusM
}
