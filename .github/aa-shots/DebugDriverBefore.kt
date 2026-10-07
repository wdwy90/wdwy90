package com.wdwy90.pullupmenu.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.LruCache
import androidx.car.app.OnDoneCallback
import androidx.car.app.ScreenManager
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.wdwy90.pullupmenu.core.ChainMenus
import com.wdwy90.pullupmenu.core.ChainPrices
import com.wdwy90.pullupmenu.core.DriveWatcher
import com.wdwy90.pullupmenu.core.MenuRepository
import com.wdwy90.pullupmenu.core.PlacePhoto
import com.wdwy90.pullupmenu.core.Prefs
import com.wdwy90.pullupmenu.core.Restaurant
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Screenshot runs only (debug build on the aa-shots branch, never shipped): sets the app's state
 * and taps rows and buttons the way the host does (their click delegates), from adb broadcasts:
 *   adb shell am broadcast -a com.wdwy90.pullupmenu.DRIVE -p com.wdwy90.pullupmenu --es cmd tap --es arg "View menu"
 * Made-up places only; no lookups, no Google calls.
 */
object DebugDriver {
    private const val TAG = "PullUpDrive"
    private var serial = 0

    fun attach(session: MenuSession) {
        val ctx = session.carContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val cmd = i.getStringExtra("cmd") ?: return
                val arg = i.getStringExtra("arg").orEmpty()
                try {
                    run(session, cmd, arg)
                    Log.i(TAG, "ok $cmd '$arg' -> ${describe(session)}")
                } catch (e: Throwable) {
                    Log.e(TAG, "FAILED $cmd '$arg'", e)
                }
            }
        }
        ContextCompat.registerReceiver(ctx, receiver, IntentFilter("com.wdwy90.pullupmenu.DRIVE"), ContextCompat.RECEIVER_EXPORTED)
        session.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = ctx.unregisterReceiver(receiver)
        })
    }

    private fun run(session: MenuSession, cmd: String, arg: String) {
        val ctx = session.carContext
        val sm = ctx.getCarService(ScreenManager::class.java)
        when (cmd) {
            "limit" -> Unit // 1.9 has no list-limit override
            "auto" -> {
                Prefs.setAutoDetect(ctx, arg == "on")
                // Mode only: stop the GPS watching it starts, so no lookup replaces the states set here.
                if (arg == "on") Handler(Looper.getMainLooper()).postDelayed({ DriveWatcher.release(DriveWatcher.OWNER_CAR) }, 800)
            }
            "perm" -> {
                field(MenuSession::class.java, "permissionMissing").setBoolean(session, arg == "on")
                (field(MenuSession::class.java, "home").get(session) as? HomeScreen)?.invalidate()
            }
            "idle" -> setState(MenuRepository.State.Idle)
            "searching" -> setState(MenuRepository.State.Searching)
            "nothing" -> setState(MenuRepository.State.NothingNearby)
            "error" -> setState(MenuRepository.State.Error(arg))
            "demo" -> MenuRepository.showDemo(ctx)
            "found" -> found(ctx, arg)
            "home" -> sm.popToRoot()
            "back" -> ctx.onBackPressedDispatcher.onBackPressed()
            "tap" -> tap(sm.top.onGetTemplate(), arg)
            "search" -> (sm.top.onGetTemplate() as SearchTemplate).searchCallbackDelegate
                .sendSearchTextChanged(arg, object : OnDoneCallback {})
            "dump" -> Log.i(TAG, "limits list=${limit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_LIST)} pane=${limit(ctx, ConstraintManager.CONTENT_LIMIT_TYPE_PANE)} api=${ctx.carAppApiLevel}")
            else -> error("unknown command $cmd")
        }
    }

    private fun limit(ctx: Context, type: Int): Any = try {
        (ctx as androidx.car.app.CarContext).getCarService(ConstraintManager::class.java).getContentLimit(type)
    } catch (e: Exception) {
        e.toString()
    }

    /**
     * arg: "name|photo|open|others|rating|address", e.g. "Burger King|1|1|2|4.1|1200 N Main St, Springfield".
     * photo 1 = a made-up placeholder image in the photo cache; open 1/0/- = Google's open now / closed now / no hours.
     */
    private fun found(ctx: Context, arg: String) {
        val p = arg.split("|")
        val name = p.getOrElse(0) { "Burger King" }
        val withPhoto = p.getOrNull(1) == "1"
        val open = when (p.getOrNull(2)) { "1" -> true; "0" -> false; else -> null }
        val others = p.getOrNull(3)?.toIntOrNull() ?: 0
        val rating = p.getOrNull(4)?.toDoubleOrNull()
        val address = p.getOrNull(5) ?: "1200 N Main St, Springfield"
        serial++
        val photos = if (withPhoto) listOf(PlacePhoto("places/dbg$serial/photos/1", "Jordan Lee", null, null)) else emptyList()
        if (withPhoto) photoCache().put("places/dbg$serial/photos/1@480", placeholder())
        val r = place(ctx, "dbg$serial", name, address, rating, open, photos, 40.0)
        val near = listOf("Taco Bell" to 75.0, "Starbucks" to 120.0, "Wendy's" to 190.0, "Chick-fil-A" to 260.0)
            .take(others).mapIndexed { i, (n, d) -> place(ctx, "dbg$serial-o$i", n, "Nearby", 4.0, true, emptyList(), d) }
        val now = System.currentTimeMillis()
        setState(MenuRepository.State.Found(r, near, now, auto = true, checkedMs = now))
    }

    private fun place(ctx: Context, id: String, name: String, address: String, rating: Double?, open: Boolean?, photos: List<PlacePhoto>, meters: Double) =
        Restaurant(
            id = id, name = name, address = address, lat = 39.8, lng = -89.6, rating = rating,
            category = if (name.contains("Starbucks")) "Coffee shop" else "Fast food restaurant",
            photos = photos, websiteUri = null, mapsUri = null, distanceMeters = meters,
            menuUrl = ChainMenus.get(ctx).menuUrlFor(name), prices = ChainPrices.get(ctx).forPlace(name),
            openNow = open, businessStatus = "OPERATIONAL",
        )

    /** A plain stand-in for a storefront photo: a warm gradient, clearly not a real picture. */
    private fun placeholder(): Bitmap {
        val bmp = Bitmap.createBitmap(480, 360, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val paint = Paint()
        paint.shader = LinearGradient(0f, 0f, 480f, 360f, Color.rgb(120, 60, 40), Color.rgb(40, 40, 52), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 480f, 360f, paint)
        paint.shader = null
        paint.color = Color.argb(200, 255, 255, 255)
        paint.textSize = 34f
        paint.isAntiAlias = true
        c.drawText("Sample photo", 140f, 190f, paint)
        return bmp
    }

    private fun tap(t: Template, label: String) {
        val rows: List<Row> = when (t) {
            is PaneTemplate -> t.pane.rows
            is ListTemplate -> t.singleList?.items.orEmpty().filterIsInstance<Row>()
            is SearchTemplate -> t.itemList?.items.orEmpty().filterIsInstance<Row>()
            else -> emptyList()
        }
        val actions: List<Action> = when (t) {
            is PaneTemplate -> t.pane.actions + t.actionStrip?.actions.orEmpty()
            is ListTemplate -> t.actionStrip?.actions.orEmpty()
            else -> emptyList()
        }
        val done = object : OnDoneCallback {}
        rows.firstOrNull { it.title.toString() == label }?.onClickDelegate?.let { it.sendClick(done); return }
        rows.firstOrNull { it.title.toString().startsWith(label) }?.onClickDelegate?.let { it.sendClick(done); return }
        actions.firstOrNull { it.title?.toString() == label }?.onClickDelegate?.let { it.sendClick(done); return }
        if (label == "search") actions.firstOrNull { it.title == null }?.onClickDelegate?.let { it.sendClick(done); return }
        error("nothing to tap called '$label' among ${rows.map { it.title }} ${actions.map { it.title }}")
    }

    private fun describe(session: MenuSession): String {
        val sm = session.carContext.getCarService(ScreenManager::class.java)
        return sm.screenStack.joinToString(" > ") { it.javaClass.simpleName } + " (" + sm.stackSize + ")"
    }

    @Suppress("UNCHECKED_CAST")
    private fun setState(s: MenuRepository.State) {
        (field(MenuRepository::class.java, "_state").get(MenuRepository) as MutableStateFlow<MenuRepository.State>).value = s
    }

    @Suppress("UNCHECKED_CAST")
    private fun photoCache(): LruCache<String, Bitmap> = field(MenuRepository::class.java, "photoCache").get(MenuRepository) as LruCache<String, Bitmap>

    private fun field(owner: Class<*>, name: String) = owner.getDeclaredField(name).apply { isAccessible = true }
}
