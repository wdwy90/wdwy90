package com.wdwy90.pullupmenu.car

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.wdwy90.pullupmenu.core.LocationWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import kotlinx.coroutines.launch

class MenuSession : Session() {
    lateinit var watcher: LocationWatcher
        private set
    var permissionMissing = false
        private set
    private var shownId: String? = null

    override fun onCreateScreen(intent: Intent): Screen {
        watcher = LocationWatcher(carContext)
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                permissionMissing = try {
                    watcher.start(); false
                } catch (e: SecurityException) {
                    true
                }
            }

            override fun onDestroy(owner: LifecycleOwner) = watcher.stop()
        })

        // When a new restaurant is detected, jump straight to its photos.
        lifecycleScope.launch {
            MenuRepository.state.collect { s ->
                if (s is MenuRepository.State.Found && s.restaurant.id != shownId) {
                    shownId = s.restaurant.id
                    val sm = carContext.getCarService(ScreenManager::class.java)
                    sm.popToRoot()
                    sm.push(RestaurantScreen(carContext, s.restaurant))
                }
            }
        }
        return HomeScreen(carContext, this)
    }
}
