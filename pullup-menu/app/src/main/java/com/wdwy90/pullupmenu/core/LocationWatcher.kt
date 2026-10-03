package com.wdwy90.pullupmenu.core

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Streams GPS fixes into an [ArrivalDetector] and kicks off a restaurant lookup
 * whenever the car comes to a stop somewhere new.
 */
class LocationWatcher(private val ctx: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(ctx)
    private val detector = ArrivalDetector()
    var lastLocation: Location? = null
        private set

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) {
                lastLocation = loc
                val sample = ArrivalDetector.Sample(
                    loc.latitude, loc.longitude, loc.time,
                    if (loc.hasSpeed()) loc.speed.toDouble() else null,
                )
                if (detector.onSample(sample)) {
                    MenuRepository.lookup(ctx, loc.latitude, loc.longitude)
                }
            }
        }
    }

    /** @throws SecurityException if location permission hasn't been granted. */
    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000)
            .setMinUpdateIntervalMillis(1_000)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    fun stop() {
        client.removeLocationUpdates(callback)
    }

    /** Manual "check now": look up wherever we are right now. */
    @SuppressLint("MissingPermission")
    fun checkNow() {
        detector.reset()
        val loc = lastLocation
        if (loc != null) {
            MenuRepository.lookup(ctx, loc.latitude, loc.longitude)
        } else {
            client.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { l ->
                    if (l != null) MenuRepository.lookup(ctx, l.latitude, l.longitude)
                }
        }
    }
}
