package com.wdwy90.pullupmenu.core

/**
 * Decides when a drive-thru visit is over: the car is well clear of the restaurant it found (past
 * any drive-thru lane or parking lot, and far beyond the 45 m search radius) on [fixesNeeded]
 * good fixes in a row, so one stray fix can't end a visit early. Pure logic so it can be unit
 * tested without Android.
 */
class DepartureDetector(
    private val leaveRadiusM: Double = 300.0,
    private val maxAccuracyM: Double = 100.0,
    private val fixesNeeded: Int = 2,
) {
    private var visit: String? = null
    private var away = 0

    /**
     * A fix while the restaurant at [placeLat], [placeLng] is shown; [visitKey] tells one visit
     * from the next. Returns true once, on the fix that ends the visit.
     */
    fun onSample(
        visitKey: String,
        placeLat: Double,
        placeLng: Double,
        lat: Double,
        lng: Double,
        accuracyM: Double?,
    ): Boolean {
        if (visitKey != visit) {
            visit = visitKey
            away = 0
        }
        if (accuracyM != null && accuracyM > maxAccuracyM) return false
        if (Geo.distanceMeters(placeLat, placeLng, lat, lng) < leaveRadiusM) {
            away = 0
            return false
        }
        away++
        return away == fixesNeeded
    }
}
