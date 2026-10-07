package com.wdwy90.pullupmenu.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.MenuRepository.State
import com.wdwy90.pullupmenu.core.Notifier
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch

class MenuSession : Session() {
    /** Only places found after this moment open by themselves (not an old stop from earlier). */
    private val startedAtMs = System.currentTimeMillis()
    var permissionMissing = false
        private set
    private var checkedStopped = false
    private var lastAutoKey: String? = null
    private var home: HomeScreen? = null

    override fun onCreateScreen(intent: Intent): Screen {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // Location may have been allowed on the phone since the last try.
            override fun onStart(owner: LifecycleOwner) {
                if (permissionMissing) applyAutoDetect(Prefs.autoDetectFlow(carContext).value)
            }

            override fun onDestroy(owner: LifecycleOwner) = DriveWatcher.release(DriveWatcher.OWNER_CAR)
        })

        val homeScreen = HomeScreen(carContext, this)
        home = homeScreen
        val found = MenuRepository.state.value as? State.Found
        val first: Screen = if (isCardIntent(intent) && found != null) {
            // The stack is empty here: seed Home first so Back from the card goes Home.
            lastAutoKey = key(found)
            screens().push(homeScreen)
            RestaurantScreen(carContext, found.restaurant)
        } else {
            homeScreen
        }

        // Dispatchers.Main (not immediate): these run after the first screen is on the stack.
        lifecycleScope.launch(Dispatchers.Main) {
            Prefs.autoDetectFlow(carContext).collect { on -> applyAutoDetect(on) }
        }
        lifecycleScope.launch(Dispatchers.Main) {
            MenuRepository.state.collect { s ->
                if (s is State.Found && s.atMs >= startedAtMs && key(s) != lastAutoKey) {
                    lastAutoKey = key(s)
                    openCard(s.restaurant)
                }
            }
        }
        // A red light, or the car has driven away from the restaurant: back to Home, ready for the next.
        lifecycleScope.launch(Dispatchers.Main) {
            merge(MenuRepository.dismissed, MenuRepository.left).collect { id ->
                val sm = screens()
                if (sm.screenStack.any { showsRestaurant(it, id) }) sm.popToRoot()
            }
        }
        return first
    }

    override fun onNewIntent(intent: Intent) {
        val found = MenuRepository.state.value as? State.Found ?: return
        if (isCardIntent(intent)) {
            lastAutoKey = key(found)
            openCard(found.restaurant)
        }
    }

    private fun applyAutoDetect(on: Boolean) {
        if (on) {
            permissionMissing = try {
                DriveWatcher.acquire(carContext, DriveWatcher.OWNER_CAR)
                false
            } catch (e: SecurityException) {
                true
            }
            if (!permissionMissing && !checkedStopped) {
                checkedStopped = true
                DriveWatcher.checkIfStopped(carContext)
            }
        } else {
            DriveWatcher.release(DriveWatcher.OWNER_CAR)
            permissionMissing = false
        }
        home?.invalidate()
    }

    private fun openCard(r: Restaurant) {
        val sm = screens()
        if (sm.stackSize == 0) return
        if ((sm.top as? RestaurantScreen)?.restaurantId == r.id) return
        sm.popToRoot()
        sm.push(RestaurantScreen(carContext, r))
    }

    /** The card, the menu lists that take its place (see [RestaurantScreen]), and what they open. */
    private fun showsRestaurant(screen: Screen, id: String) = (screen as? ShowsRestaurant)?.restaurantId == id

    private fun screens(): ScreenManager = carContext.getCarService(ScreenManager::class.java)
    private fun isCardIntent(i: Intent) = i.data?.scheme == Notifier.CAR_INTENT_SCHEME
    private fun key(f: State.Found) = "${f.restaurant.id}@${f.atMs}"
}
