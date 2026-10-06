package com.wdwy90.pullupmenu.core

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * One GPS stream and one [ArrivalDetector] shared by the car app and the phone service, so the
 * two never double-bill Places lookups. GPS runs while at least one owner holds it.
 * Call from the main thread. Locations stay in memory only.
 */
object DriveWatcher {
    const val OWNER_CAR = "car"
    const val OWNER_PHONE = "phone"

    private const val FRESH_FIX_MS = 30_000L
    private const val STOPPED_SPEED_MPS = 3.0
    private const val PERMISSION_MESSAGE = "Location permission needed. Open Pull Up Menu on your phone."

    private val owners = mutableSetOf<String>()
    private var client: FusedLocationProviderClient? = null
    private var appCtx: Context? = null
    private val detector = ArrivalDetector()
    private val redLight = RedLightFilter()

    val isWatching: Boolean get() = owners.isNotEmpty()

    @Volatile var lastLocation: Location? = null
        private set

    private val _fixes = MutableSharedFlow<Location>(
        replay = 0, extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Every GPS fix while watching. */
    val fixes: SharedFlow<Location> = _fixes

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            for (loc in result.locations) onFix(loc)
        }
    }

    /** GPS runs while at least one owner holds it. @throws SecurityException without location permission. */
    @SuppressLint("MissingPermission")
    fun acquire(ctx: Context, owner: String) {
        val app = ctx.applicationContext
        if (owners.isEmpty()) {
            if (!hasPermission(app)) throw SecurityException("Location permission not granted")
            val c = LocationServices.getFusedLocationProviderClient(app)
            val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 3_000)
                .setMinUpdateIntervalMillis(1_000)
                .build()
            c.requestLocationUpdates(request, callback, Looper.getMainLooper())
            client = c
        }
        appCtx = app
        owners += owner
    }

    fun release(owner: String) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        client?.removeLocationUpdates(callback)
        client = null
        detector.reset()
        redLight.clear()
    }

    /**
     * Manual "check now": look up wherever we are (fix < 30 s old, else a one-shot fix). Never throws;
     * a missing permission becomes a [MenuRepository.State.Error].
     */
    fun checkNow(ctx: Context) {
        val app = ctx.applicationContext
        val onMissing = { MenuRepository.fail(PERMISSION_MESSAGE) }
        // Set once a one-shot fix is pending; -1 means the result came back right away.
        var gen = -1
        currentFix(app, showSearching = true, onMissingPermission = onMissing) { loc ->
            // Something newer (Demo, another lookup) replaced our "Searching" while we waited: drop this.
            if (gen != -1 && MenuRepository.generation != gen) return@currentFix
            if (loc == null) {
                MenuRepository.fail("Couldn't get your location. Try again in a moment.")
                return@currentFix
            }
            // Auto-detect shouldn't look up this same spot again.
            detector.markLookedUp(loc.latitude, loc.longitude)
            MenuRepository.lookup(app, loc.latitude, loc.longitude, auto = false)
        }
        gen = MenuRepository.generation
    }

    /**
     * Car app just opened with auto-detect ON: if we're already stopped (speed unknown or < 3 m/s)
     * somewhere new and it's not a known red light, look up right away.
     */
    fun checkIfStopped(ctx: Context) {
        val app = ctx.applicationContext
        currentFix(app, showSearching = false, onMissingPermission = {}) { loc ->
            if (loc == null) return@currentFix
            val lat = loc.latitude
            val lng = loc.longitude
            val stopped = !loc.hasSpeed() || loc.speed < STOPPED_SPEED_MPS
            if (!stopped || redLight.isIgnored(lat, lng) || detector.wasLookedUpNear(lat, lng)) return@currentFix
            detector.markLookedUp(lat, lng)
            // Now, not the reused fix's time: the drive-off window starts when the card can appear.
            redLight.onTrigger(lat, lng, SystemClock.elapsedRealtimeNanos() / 1_000_000)
            MenuRepository.lookup(app, lat, lng, auto = true)
        }
    }

    private fun onFix(loc: Location) {
        lastLocation = loc
        _fixes.tryEmit(loc)
        val ctx = appCtx ?: return
        val lat = loc.latitude
        val lng = loc.longitude
        val t = timeMs(loc)
        val speed = if (loc.hasSpeed()) loc.speed.toDouble() else null
        if (redLight.onSample(lat, lng, t, speed)) MenuRepository.dismissFalseAlarm(ctx)
        if (detector.onSample(ArrivalDetector.Sample(lat, lng, t, speed)) && !redLight.isIgnored(lat, lng)) {
            redLight.onTrigger(lat, lng, t)
            MenuRepository.lookup(ctx, lat, lng, auto = true)
        }
    }

    /** Uses a fix under 30 s old, else asks for a one-shot fix. [onResult] gets null if none came. */
    @SuppressLint("MissingPermission")
    private fun currentFix(
        app: Context,
        showSearching: Boolean,
        onMissingPermission: () -> Unit,
        onResult: (Location?) -> Unit,
    ) {
        val last = lastLocation
        if (last != null && ageMs(last) < FRESH_FIX_MS) {
            onResult(last)
            return
        }
        if (!hasPermission(app)) {
            onMissingPermission()
            return
        }
        if (showSearching) MenuRepository.searching()
        try {
            LocationServices.getFusedLocationProviderClient(app)
                .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener { l ->
                    if (l != null) lastLocation = l
                    onResult(l)
                }
                .addOnFailureListener { onResult(null) }
        } catch (e: SecurityException) {
            onMissingPermission()
        }
    }

    /** Same clock for every fix: time since boot, unaffected by GPS/device clock differences. */
    private fun timeMs(loc: Location): Long = loc.elapsedRealtimeNanos / 1_000_000

    private fun ageMs(loc: Location): Long =
        SystemClock.elapsedRealtimeNanos() / 1_000_000 - timeMs(loc)

    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
