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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * One GPS stream and one [ArrivalDetector] shared by the car app and the phone service, so the
 * two never double-bill Places lookups. GPS runs while at least one owner holds it.
 * Call from the main thread. Locations stay in memory only.
 */
@SuppressLint("StaticFieldLeak") // holds the application context only, never an activity or service
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
    private val departure = DepartureDetector()

    val isWatching: Boolean get() = owners.isNotEmpty()

    private val _watching = MutableStateFlow(false)
    /** True while GPS watching runs (for the car app, the phone service, or both). */
    val watching: StateFlow<Boolean> = _watching

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
        _watching.value = true
    }

    fun release(owner: String) {
        if (!owners.remove(owner) || owners.isNotEmpty()) return
        _watching.value = false
        client?.removeLocationUpdates(callback)
        client = null
        detector.reset()
        redLight.clear()
        departure.reset()
    }

    /**
     * Manual "check now": look up wherever we are (fix < 30 s old, else a one-shot fix). Never throws;
     * a missing permission becomes a [MenuRepository.State.Error]. Does nothing while a lookup is
     * already on its way: each one is billed, and its answer is coming.
     */
    fun checkNow(ctx: Context) {
        if (MenuRepository.state.value == MenuRepository.State.Searching && MenuRepository.lookupInFlight) return
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
            // Auto-detect counts this lookup as its own: it won't look up this same spot again.
            val id = detector.markLookedUp(loc.latitude, loc.longitude)
            MenuRepository.lookup(app, loc.latitude, loc.longitude, auto = false) { report(id, it) }
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
            val id = detector.markLookedUp(lat, lng)
            // Now, not the reused fix's time: the drive-off window starts when the card can appear.
            autoLookup(app, lat, lng, id, Prefs.radiusMeters(app), nowMs())
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
        val accuracy = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null
        if (redLight.onSample(lat, lng, t, speed)) {
            // A red light: what was looked up there says nothing about where the car stops next.
            detector.forget()
            MenuRepository.dismissFalseAlarm(ctx)
        }
        MenuRepository.visit?.let { visit ->
            val r = visit.restaurant
            if (departure.onSample("${r.id}@${visit.atMs}", r.lat, r.lng, lat, lng, accuracy)) {
                MenuRepository.visitOver(ctx, visit)
            }
        }
        if (detector.onSample(ArrivalDetector.Sample(lat, lng, t, speed, accuracy)) && !redLight.isIgnored(lat, lng)) {
            autoLookup(ctx, lat, lng, detector.lookupId, detector.radiusM, t)
        }
    }

    /**
     * An automatic lookup at [lat], [lng], started at [triggerMs] (fix clock). A new card from it is
     * withdrawn if the car drives off fast: a red light ([RedLightFilter]). While a visit is on screen
     * the lookup runs in the background (see [MenuRepository.lookup]), and only a new place counts.
     */
    private fun autoLookup(ctx: Context, lat: Double, lng: Double, id: Int, radiusM: Double, triggerMs: Long) {
        val background = MenuRepository.visitOnScreen
        if (!background) redLight.onTrigger(lat, lng, triggerMs)
        MenuRepository.lookup(ctx, lat, lng, auto = true, radiusM = radiusM) { outcome ->
            report(id, outcome)
            if (background && outcome is MenuRepository.Outcome.Found && outcome.isNew) {
                redLight.onTrigger(lat, lng, triggerMs)
            }
        }
    }

    /** Tells the detector how lookup [id] went, so it knows which stops are answered and what to retry. */
    private fun report(id: Int, outcome: MenuRepository.Outcome) {
        when (outcome) {
            is MenuRepository.Outcome.Found -> detector.found(id, outcome.place.lat, outcome.place.lng)
            MenuRepository.Outcome.NothingNearby -> detector.nothingFound(id)
            is MenuRepository.Outcome.Failed -> detector.failed(id, outcome.retryable, nowMs())
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

    /** Now, on the fixes' clock. */
    private fun nowMs(): Long = SystemClock.elapsedRealtimeNanos() / 1_000_000

    private fun ageMs(loc: Location): Long = nowMs() - timeMs(loc)

    /**
     * Precise location only. An approximate fix is off by up to a few kilometers, so it can't tell
     * which drive-thru the car is in; the car and phone then ask for precise location instead.
     */
    private fun hasPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
