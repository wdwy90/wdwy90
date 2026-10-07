package com.wdwy90.pullupmenu.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.wdwy90.pullupmenu.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException

/** App-wide state shared by the car screen and the phone UI. In memory only. */
object MenuRepository {

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Found(
            val restaurant: Restaurant,
            val others: List<Restaurant>,
            /** System.currentTimeMillis() when found. */
            val atMs: Long,
            /** True if this came from automatic detection. */
            val auto: Boolean,
            /** True once the user picked this place from the nearby list. */
            val chosen: Boolean = false,
            /** When Google last answered for this place (a later check that confirms it refreshes this, not [atMs]). */
            val checkedMs: Long = atMs,
        ) : State
        data object NothingNearby : State
        data class Error(val message: String) : State
    }

    /** How a lookup ended, for [DriveWatcher]. */
    sealed interface Outcome {
        /** [place] is on screen: just found ([isNew]), or the visit going on after a background check. */
        data class Found(val place: Restaurant, val isNew: Boolean) : Outcome
        data object NothingNearby : Outcome
        /** No answer. [retryable] when the same lookup may work later (no internet, a timeout, Google busy). */
        data class Failed(val retryable: Boolean) : Outcome
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _dismissed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a restaurant id when its auto-detected card is withdrawn because it looked like a red light. */
    val dismissed: SharedFlow<String> = _dismissed

    private val _left = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a restaurant id when the car has driven away from it: that visit is over. */
    val left: SharedFlow<String> = _left

    /**
     * The restaurant found last (never the demo), until the car drives away from it or its card is
     * withdrawn as a red light. It outlives [state]: another check on the way out (stopped at a light,
     * say) mustn't keep this visit's car card and notification up for good. Main thread only.
     */
    internal var visit: State.Found? = null
        private set

    /** Bumped by every change that supersedes a pending request (see [DriveWatcher.checkNow]). Main thread only. */
    internal var generation = 0
        private set

    private var lookupJob: Job? = null
    private var lookupIsAuto = false

    /** True while [visit] is what's on screen: an automatic check then runs in the background (see [lookup]). */
    internal val visitOnScreen: Boolean get() = visit.let { it != null && _state.value == it }

    /** True while a Places search is in flight. Main thread only. */
    internal val lookupInFlight: Boolean get() = lookupJob?.isActive == true

    /** Phone screens in front of the user (resumed). Main thread only. */
    internal var phoneScreensResumed = 0

    private val photosInFlight = ConcurrentHashMap<String, Deferred<Bitmap?>>()

    /** Room for a restaurant's photos at screen width (up to about 7 MB each): an eighth of the heap, 24 MB at least. */
    private val photoCache = object : LruCache<String, Bitmap>(
        maxOf(24L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 8).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    /**
     * Looks up the fast-food places within [radiusM] (by default the app's radius) of [lat], [lng] and
     * shows the nearest. [onDone] hears how it ended, unless something newer replaced it first.
     *
     * An automatic check ([auto]) while [visit] is on screen runs in the background: the line moved
     * on, or the car pulled into the place next door. The screen only changes when the car is now at
     * another place (see [VisitCheck]); otherwise nothing is shown or announced again, and a failure
     * isn't shown either.
     */
    fun lookup(
        ctx: Context,
        lat: Double,
        lng: Double,
        auto: Boolean = false,
        radiusM: Double? = null,
        onDone: (Outcome) -> Unit = {},
    ) {
        val appCtx = ctx.applicationContext
        generation++
        val key = Prefs.apiKey(appCtx)
        if (key.isBlank()) {
            fail(
                if (Prefs.hasBuiltInKey()) "Places key missing from this build."
                else "Add your Google Places API key in the phone app."
            )
            onDone(Outcome.Failed(retryable = false))
            return
        }
        lookupJob?.cancel()
        lookupIsAuto = auto
        val background = if (auto && visitOnScreen) visit else null
        if (background == null) _state.value = State.Searching
        lookupJob = scope.launch {
            val results = try {
                // The bundled lists take a moment to parse the first time: not on the main thread.
                val (chains, prices) = withContext(Dispatchers.IO) { ChainMenus.get(appCtx) to ChainPrices.get(appCtx) }
                PlacesClient(key, AppIdentity.headers(appCtx))
                    .nearbyRestaurants(lat, lng, radiusM ?: Prefs.radiusMeters(appCtx))
                    .map { it.copy(menuUrl = chains.menuUrlFor(it.name), prices = prices.forPlace(it.name)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("PullUp", "lookup", e)
                if (background == null) _state.value = State.Error(errorMessage(e))
                onDone(Outcome.Failed(retryable = isRetryable(e)))
                return@launch
            }
            if (background != null) {
                // A pick from the list or the end of the visit came first: that stands.
                if (visit !== background || _state.value !== background) return@launch
                if (VisitCheck.staysAt(background.restaurant, background.chosen, results)) {
                    // Still here. Google's open/closed answer is taken from the fresh result; the visit
                    // itself (its time, its photos and menu) stays as it was.
                    val kept = background.restaurant
                    val fresh = results.firstOrNull { it.id == kept.id }
                    if (fresh != null) {
                        val updated = background.copy(
                            restaurant = kept.copy(openNow = fresh.openNow, businessStatus = fresh.businessStatus),
                            checkedMs = System.currentTimeMillis(),
                        )
                        visit = updated
                        _state.value = updated
                    }
                    onDone(if (results.isEmpty()) Outcome.NothingNearby else Outcome.Found(visit?.restaurant ?: kept, isNew = false))
                    return@launch
                }
            }
            val result = if (results.isEmpty()) State.NothingNearby
            else State.Found(results.first(), results.drop(1), System.currentTimeMillis(), auto)
            _state.value = result
            if (result is State.Found) {
                visit = result
                Prefs.setLastDetected(appCtx, result.restaurant.prices?.chain, result.atMs)
                // A check made on a phone screen opens the restaurant there; no need to announce it too.
                if (auto || phoneScreensResumed == 0) {
                    try {
                        Notifier.arrival(appCtx, result.restaurant, carBanner = auto && Prefs.carBanner(appCtx))
                    } catch (e: Exception) {
                        // A failed notification must not hide the result.
                    }
                }
                onDone(Outcome.Found(result.restaurant, isNew = true))
            } else {
                onDone(Outcome.NothingNearby)
            }
        }
    }

    /** User picked a different place from the nearby list. */
    fun choose(ctx: Context, restaurant: Restaurant) {
        val current = _state.value as? State.Found ?: return
        // The user's pick wins over a background check still running.
        generation++
        lookupJob?.cancel()
        if (!restaurant.isDemo) Prefs.setLastDetected(ctx.applicationContext, restaurant.prices?.chain, current.atMs)
        val all = listOf(current.restaurant) + current.others
        val chosen = current.copy(restaurant = restaurant, others = all.filter { it.id != restaurant.id }, chosen = true)
        _state.value = chosen
        // The place picked is the one this visit is at.
        if (!restaurant.isDemo) visit = chosen
    }

    /**
     * Shows a sample card (for Play reviewers and first-time users). No notification. Right away, so
     * the bundled lists may be parsed here on first use: a one-off moment after a tap.
     */
    fun showDemo(ctx: Context) {
        val appCtx = ctx.applicationContext
        generation++
        lookupJob?.cancel()
        val chains = ChainMenus.get(appCtx)
        val prices = ChainPrices.get(appCtx)
        val chain = prices.demoChainName() ?: "McDonald's"
        val demo = Restaurant(
            id = DEMO_ID,
            name = "$chain (demo)",
            address = "Demo. Not a real location.",
            lat = 0.0,
            lng = 0.0,
            rating = null,
            category = "Fast food restaurant",
            photos = emptyList(),
            websiteUri = null,
            mapsUri = null,
            menuUrl = chains.menuUrlFor(chain),
            prices = prices.forPlace(chain),
            isDemo = true,
        )
        _state.value = State.Found(demo, emptyList(), System.currentTimeMillis(), auto = false)
    }

    /**
     * The car drove off fast right after an automatic card appeared: it was a red light.
     * Withdraws the card (and an automatic lookup still in flight).
     */
    fun dismissFalseAlarm(ctx: Context) {
        when (val s = _state.value) {
            is State.Found -> if (s.auto) {
                generation++
                if (visit == s) visit = null
                _state.value = State.Idle
                Notifier.cancelArrival(ctx.applicationContext)
                _dismissed.tryEmit(s.restaurant.id)
            }
            State.Searching -> if (lookupIsAuto) {
                generation++
                lookupJob?.cancel()
                _state.value = State.Idle
            }
            else -> Unit
        }
    }

    /**
     * The car has driven away from [visit]'s restaurant: back to ready, quietly. Its car card and the
     * arrival notification go; the phone's restaurant screen stays open for anyone reading it. A
     * newer result (another stop on the way out found nothing, say) stays on screen. Does nothing
     * once a newer visit has replaced [visit].
     */
    fun visitOver(ctx: Context, visit: State.Found) {
        if (visit != this.visit) return
        this.visit = null
        if (_state.value == visit) _state.value = State.Idle
        Notifier.cancelArrival(ctx.applicationContext)
        _left.tryEmit(visit.restaurant.id)
    }

    /** Used by [DriveWatcher.checkNow] while it waits for a location fix. */
    internal fun searching() {
        generation++
        lookupJob?.cancel()
        lookupIsAuto = false
        _state.value = State.Searching
    }

    private fun errorMessage(e: Exception): String = when (e) {
        is UnknownHostException -> "No internet connection. Try again."
        is SocketTimeoutException -> "Google Maps took too long. Try again."
        else -> "Couldn't look up this place. Try again."
    }

    /** Network trouble or Google busy: yes. A rejected request (a bad key, say) or a bad answer: no. */
    private fun isRetryable(e: Exception): Boolean = when (e) {
        is PlacesClient.HttpException -> e.retryable
        is IOException -> true
        else -> false
    }

    /** Used by [DriveWatcher.checkNow] when it can't get a location. */
    internal fun fail(message: String) {
        generation++
        lookupJob?.cancel()
        _state.value = State.Error(message)
    }

    /**
     * A place photo, cached in memory. Photo loads are billed one by one, so callers asking for the
     * same photo at the same time (the restaurant header and the photo grid) share one download.
     */
    suspend fun photo(ctx: Context, photoName: String, maxWidthPx: Int): Bitmap? {
        val cacheKey = "$photoName@$maxWidthPx"
        photoCache.get(cacheKey)?.let { return it }
        val appCtx = ctx.applicationContext
        val pending = photosInFlight[cacheKey] ?: scope.async {
            PlacesClient(Prefs.apiKey(appCtx), AppIdentity.headers(appCtx)).photo(photoName, maxWidthPx)
                ?.also { photoCache.put(cacheKey, it) }
        }.also { deferred ->
            photosInFlight[cacheKey] = deferred
            deferred.invokeOnCompletion { photosInFlight.remove(cacheKey, deferred) }
        }
        return pending.await()
    }

    private const val DEMO_ID = "demo"
}
