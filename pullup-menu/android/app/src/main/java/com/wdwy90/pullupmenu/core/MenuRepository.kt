package com.wdwy90.pullupmenu.core

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.wdwy90.pullupmenu.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import java.net.UnknownHostException
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
        ) : State
        data object NothingNearby : State
        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val _dismissed = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Emits a restaurant id when its auto-detected card is withdrawn because it looked like a red light. */
    val dismissed: SharedFlow<String> = _dismissed

    /** Bumped by every change that supersedes a pending request (see [DriveWatcher.checkNow]). Main thread only. */
    internal var generation = 0
        private set

    private var lookupJob: Job? = null
    private var lookupIsAuto = false

    private val photoCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun lookup(ctx: Context, lat: Double, lng: Double, auto: Boolean = false) {
        val appCtx = ctx.applicationContext
        generation++
        val key = Prefs.apiKey(appCtx)
        if (key.isBlank()) {
            fail(
                if (Prefs.hasBuiltInKey()) "Places key missing from this build."
                else "Add your Google Places API key in the phone app."
            )
            return
        }
        lookupJob?.cancel()
        lookupIsAuto = auto
        _state.value = State.Searching
        lookupJob = scope.launch {
            val result = try {
                val chains = ChainMenus.get(appCtx)
                val prices = ChainPrices.get(appCtx)
                val results = PlacesClient(key, AppIdentity.headers(appCtx))
                    .nearbyRestaurants(lat, lng, Prefs.radiusMeters(appCtx))
                    .map { it.copy(menuUrl = chains.menuUrlFor(it.name), prices = prices.forPlace(it.name)) }
                if (results.isEmpty()) State.NothingNearby
                else State.Found(results.first(), results.drop(1), System.currentTimeMillis(), auto)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UnknownHostException) {
                State.Error("No internet connection. Try again.")
            } catch (e: SocketTimeoutException) {
                State.Error("Google Maps took too long. Try again.")
            } catch (e: Exception) {
                if (BuildConfig.DEBUG) Log.w("PullUp", "lookup", e)
                State.Error("Couldn't look up this place. Try again.")
            }
            _state.value = result
            if (result is State.Found) {
                try {
                    Notifier.arrival(appCtx, result.restaurant, carBanner = auto && Prefs.carBanner(appCtx))
                } catch (e: Exception) {
                    // A failed notification must not hide the result.
                }
            }
        }
    }

    /** User picked a different place from the nearby list. */
    fun choose(restaurant: Restaurant) {
        val current = _state.value as? State.Found ?: return
        val all = listOf(current.restaurant) + current.others
        _state.value = current.copy(restaurant = restaurant, others = all.filter { it.id != restaurant.id })
    }

    /** Shows a sample card (for Play reviewers and first-time users). No notification. */
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

    /** Used by [DriveWatcher.checkNow] while it waits for a location fix. */
    internal fun searching() {
        generation++
        lookupJob?.cancel()
        lookupIsAuto = false
        _state.value = State.Searching
    }

    /** Used by [DriveWatcher.checkNow] when it can't get a location. */
    internal fun fail(message: String) {
        generation++
        lookupJob?.cancel()
        _state.value = State.Error(message)
    }

    suspend fun photo(ctx: Context, photoName: String, maxWidthPx: Int): Bitmap? {
        val cacheKey = "$photoName@$maxWidthPx"
        photoCache.get(cacheKey)?.let { return it }
        val appCtx = ctx.applicationContext
        return PlacesClient(Prefs.apiKey(appCtx), AppIdentity.headers(appCtx)).photo(photoName, maxWidthPx)
            ?.also { photoCache.put(cacheKey, it) }
    }

    private const val DEMO_ID = "demo"
}
