package com.wdwy90.pullupmenu.core

import android.Manifest
import android.app.NotificationManager
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Field

/**
 * Leaving a restaurant, through [DriveWatcher]'s handling of location fixes: the visit ends once the
 * car is well away, even if another check on the way out has replaced the restaurant on screen. Made-up
 * places and fixes; no GPS or Places lookups.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DriveAwayTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val left = ArrayList<String>()
    private lateinit var watch: Job
    private var clockMs = 1_000_000L

    @Before
    fun startClean() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        // Singletons outlive a test: start from no visit, no count and nothing on screen.
        field(MenuRepository::class.java, "visit").set(MenuRepository, null)
        (field(DriveWatcher::class.java, "departure").get(DriveWatcher) as DepartureDetector).reset()
        // What acquire() would set, without starting real GPS.
        field(DriveWatcher::class.java, "appCtx").set(DriveWatcher, app)
        setState(MenuRepository.State.Idle)
        watch = CoroutineScope(Dispatchers.Unconfined).launch { MenuRepository.left.collect { left += it } }
    }

    @After
    fun stop() {
        watch.cancel()
        field(DriveWatcher::class.java, "appCtx").set(DriveWatcher, null)
    }

    @Test
    fun drivingAwayEndsTheVisit() {
        val place = show(restaurant("bk", 39.8, -89.6))
        Notifier.arrival(app, place, carBanner = false)
        assertEquals(1, notifications())

        drive(place, 0.0) // in the lane
        drive(place, 150.0) // pulling out
        drive(place, 400.0) // first fix well away
        assertTrue(MenuRepository.state.value is MenuRepository.State.Found)
        drive(place, 450.0) // second in a row: the visit is over

        assertEquals(MenuRepository.State.Idle, MenuRepository.state.value)
        assertEquals(listOf(place.id), left)
        assertEquals(0, notifications())
        assertNull(MenuRepository.visit)
        drive(place, 900.0) // reported once
        assertEquals(listOf(place.id), left)
    }

    @Test
    fun aStopOnTheWayOutDoesNotKeepTheOldCard() {
        val place = show(restaurant("bk", 39.8, -89.6))
        Notifier.arrival(app, place, carBanner = false)
        // Stopped at a light on the way out: auto-detect checked there and found nothing.
        setState(MenuRepository.State.NothingNearby)

        drive(place, 400.0)
        drive(place, 450.0)

        // The car card and the notification for the restaurant still go...
        assertEquals(listOf(place.id), left)
        assertEquals(0, notifications())
        // ...and the newer result stays on screen.
        assertEquals(MenuRepository.State.NothingNearby, MenuRepository.state.value)
    }

    @Test
    fun aPlacePickedFromTheListIsTheVisit() {
        val first = show(restaurant("bk", 39.8, -89.6))
        val next = restaurant("tb", 39.8002, -89.6) // next door, about 22 m north
        MenuRepository.choose(app, next)

        drive(first, 400.0)
        drive(first, 450.0)

        assertEquals(listOf(next.id), left)
        assertEquals(MenuRepository.State.Idle, MenuRepository.state.value)
    }

    @Test
    fun theDemoNeverEnds() {
        MenuRepository.showDemo(app)
        val far = restaurant("far", 39.8, -89.6)
        drive(far, 5_000.0)
        drive(far, 5_100.0)

        assertTrue(left.isEmpty())
        assertTrue((MenuRepository.state.value as MenuRepository.State.Found).restaurant.isDemo)
    }

    /** Shows [r] (Google-sourced, not the demo) through the public chooser path, as the phone does. */
    private fun show(r: Restaurant): Restaurant {
        MenuRepository.showDemo(app)
        MenuRepository.choose(app, r)
        return r
    }

    private fun restaurant(id: String, lat: Double, lng: Double) = Restaurant(
        id = id,
        name = "Burger King",
        address = "1200 Main St, Springfield, IL 62701",
        lat = lat,
        lng = lng,
        rating = null,
        category = "Fast food restaurant",
        photos = emptyList(),
        websiteUri = null,
        mapsUri = null,
        menuUrl = null,
        prices = null,
    )

    /** A fix [metersNorth] of [from], driving at city speed (so it never looks like a drive-thru stop). */
    private fun drive(from: Restaurant, metersNorth: Double) {
        clockMs += 3_000
        val loc = Location("test").apply {
            latitude = from.lat + metersNorth / 111_195.0
            longitude = from.lng
            accuracy = 8f
            speed = 12f
            time = clockMs
            elapsedRealtimeNanos = clockMs * 1_000_000
        }
        val onFix = DriveWatcher::class.java.getDeclaredMethod("onFix", Location::class.java)
        onFix.isAccessible = true
        onFix.invoke(DriveWatcher, loc)
    }

    private fun notifications() =
        shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications.size

    @Suppress("UNCHECKED_CAST")
    private fun setState(s: MenuRepository.State) {
        (field(MenuRepository::class.java, "_state").get(MenuRepository) as MutableStateFlow<MenuRepository.State>).value = s
    }

    private fun field(owner: Class<*>, name: String): Field =
        owner.getDeclaredField(name).apply { isAccessible = true }
}
