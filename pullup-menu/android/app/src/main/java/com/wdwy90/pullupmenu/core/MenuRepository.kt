package com.wdwy90.pullupmenu.core

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** App-wide state shared by the car screen and the phone UI. */
object MenuRepository {

    sealed interface State {
        data object Idle : State
        data object Searching : State
        data class Found(val restaurant: Restaurant, val others: List<Restaurant>) : State
        data object NothingNearby : State
        data class Error(val message: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    private val photoCache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun lookup(ctx: Context, lat: Double, lng: Double) {
        val appCtx = ctx.applicationContext
        val key = Prefs.apiKey(appCtx)
        if (key.isBlank()) {
            _state.value = State.Error("Add your Google Places API key in the phone app.")
            return
        }
        _state.value = State.Searching
        scope.launch {
            _state.value = try {
                val chains = ChainMenus.get(appCtx)
                val results = PlacesClient(key).nearbyRestaurants(lat, lng, Prefs.radiusMeters(appCtx))
                    .map { it.copy(menuUrl = chains.menuUrlFor(it.name)) }
                if (results.isEmpty()) State.NothingNearby
                else State.Found(results.first(), results.drop(1)).also {
                    Notifier.arrival(appCtx, it.restaurant)
                }
            } catch (e: Exception) {
                State.Error(e.message ?: "Lookup failed")
            }
        }
    }

    /** User picked a different place from the nearby list. */
    fun choose(restaurant: Restaurant) {
        val current = _state.value as? State.Found ?: return
        val all = listOf(current.restaurant) + current.others
        _state.value = State.Found(restaurant, all.filter { it.id != restaurant.id })
    }

    suspend fun photo(ctx: Context, photoName: String, maxWidthPx: Int): Bitmap? {
        val cacheKey = "$photoName@$maxWidthPx"
        photoCache.get(cacheKey)?.let { return it }
        return PlacesClient(Prefs.apiKey(ctx)).photo(photoName, maxWidthPx)
            ?.also { photoCache.put(cacheKey, it) }
    }
}
