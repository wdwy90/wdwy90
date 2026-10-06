package com.wdwy90.pullupmenu.core

/**
 * Decides when phone watching should stop by itself: parked (no move beyond [parkedRadiusM])
 * for [parkedMs], or running for [maxMs] in total. Pure logic. Use one clock for all calls.
 */
class DriveEndDetector(
    private val parkedRadiusM: Double = 200.0,
    private val parkedMs: Long = 15 * 60_000L,
    private val maxMs: Long = 4 * 60 * 60_000L,
) {
    private var startTime: Long? = null
    private var hasAnchorPos = false
    private var anchorLat = 0.0
    private var anchorLng = 0.0
    private var anchorTime: Long? = null

    /**
     * Starts the clocks before any fix arrives, so watching still stops after [parkedMs]
     * (or [maxMs]) when no fix ever comes. No-op once started.
     */
    fun start(timeMs: Long) {
        if (startTime == null) {
            startTime = timeMs
            anchorTime = timeMs
        }
    }

    /** Feed a location fix. Returns true when watching should stop. */
    fun onSample(lat: Double, lng: Double, timeMs: Long): Boolean {
        if (startTime == null) startTime = timeMs
        if (!hasAnchorPos || Geo.distanceMeters(anchorLat, anchorLng, lat, lng) > parkedRadiusM) {
            // The first real position replaces the placeholder anchor set by [start].
            hasAnchorPos = true
            anchorLat = lat
            anchorLng = lng
            anchorTime = timeMs
        }
        return onTick(timeMs)
    }

    /** Call periodically (fixes may stop coming while parked). False before [start] or the first sample. */
    fun onTick(timeMs: Long): Boolean {
        val anchor = anchorTime ?: return false
        val start = startTime ?: return false
        return timeMs - anchor >= parkedMs || timeMs - start >= maxMs
    }
}
