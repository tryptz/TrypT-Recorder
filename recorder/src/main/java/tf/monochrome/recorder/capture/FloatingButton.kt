package tf.monochrome.recorder.capture

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The floating record button: a small overlay window that stays on top of
 * every app, so a recording can be started, paused and stopped without coming
 * back here. Drag it anywhere; let go and it tucks against the nearer edge.
 *
 * The window hosts Compose. Outside an Activity nothing supplies the lifecycle
 * and saved-state owners Compose looks up the view tree for, so this window
 * carries its own ([OverlayOwner]).
 */
class FloatingButton(context: Context, private val actions: Actions) {
    interface Actions {
        fun record()
        fun pause()
        fun resume()
        fun stop()
        fun openApp()
        fun hide()
    }

    enum class Side { LEFT, RIGHT }

    /**
     * A window context, not the service itself: WindowManager from a
     * non-visual context is flagged on Android 11+ and reports the wrong
     * metrics.
     */
    private val ctx: Context = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    } else {
        context
    }
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val prefs = context.getSharedPreferences("floating_button", Context.MODE_PRIVATE)
    private val margin = (8 * ctx.resources.displayMetrics.density).roundToInt()

    private val side = mutableStateOf(if (prefs.getBoolean(K_RIGHT, true)) Side.RIGHT else Side.LEFT)
    private var root: DragFrame? = null
    private var owner: OverlayOwner? = null
    private var hidden = false

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or (if (side.value == Side.RIGHT) Gravity.END else Gravity.START)
        x = margin
        y = prefs.getInt(K_Y, (ctx.resources.displayMetrics.heightPixels * 0.3f).roundToInt())
        title = "Recorder button"
    }

    fun show() {
        if (root != null) return
        val o = OverlayOwner().also { it.start() }
        owner = o
        val compose = ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                FloatingButtonContent(
                    side = side.value,
                    actions = actions,
                )
            }
        }
        val frame = DragFrame(ctx).apply {
            addView(compose)
            setViewTreeLifecycleOwner(o)
            setViewTreeSavedStateRegistryOwner(o)
        }
        root = frame
        clampY()
        wm.addView(frame, params)
        applyHidden()
    }

    fun dismiss() {
        root?.let { runCatching { wm.removeView(it) } }
        root = null
        owner?.destroy()
        owner = null
    }

    /** Off screen while recording, when the user asked for a clean capture. */
    fun setHidden(hide: Boolean) {
        if (hidden == hide) return
        hidden = hide
        applyHidden()
    }

    fun onConfigurationChanged() {
        clampY()
        update()
    }

    private fun applyHidden() {
        val r = root ?: return
        r.visibility = if (hidden) View.GONE else View.VISIBLE
        params.flags = if (hidden) {
            params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
        update()
    }

    private fun update() {
        root?.let { runCatching { wm.updateViewLayout(it, params) } }
    }

    private fun screenSize(): Pair<Int, Int> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val b = wm.currentWindowMetrics.bounds
        b.width() to b.height()
    } else {
        @Suppress("DEPRECATION")
        val dm = android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }
        dm.widthPixels to dm.heightPixels
    }

    private fun clampY() {
        val (_, h) = screenSize()
        val viewH = root?.height?.takeIf { it > 0 } ?: 0
        params.y = params.y.coerceIn(0, (h - viewH - margin).coerceAtLeast(0))
    }

    // Dragging works in absolute (START-gravity) coordinates; on release the
    // window is re-anchored to the nearer edge, so that the controls it
    // expands into open towards the middle of the screen, not off its edge.
    private fun onDragStart() {
        val r = root ?: return
        if (params.gravity and Gravity.END == Gravity.END) {
            val (w, _) = screenSize()
            params.x = w - params.x - r.width
        }
        params.gravity = Gravity.TOP or Gravity.START
    }

    private fun onDrag(dx: Float, dy: Float) {
        params.x += dx.roundToInt()
        params.y += dy.roundToInt()
        clampY()
        update()
    }

    private fun onDragEnd() {
        val r = root ?: return
        val (w, _) = screenSize()
        val toRight = params.x + r.width / 2 > w / 2
        side.value = if (toRight) Side.RIGHT else Side.LEFT
        params.gravity = Gravity.TOP or (if (toRight) Gravity.END else Gravity.START)
        params.x = margin
        update()
        prefs.edit {
            putBoolean(K_RIGHT, toRight)
            putInt(K_Y, params.y)
        }
    }

    /**
     * Intercepts a gesture only once it has moved past touch slop, so taps
     * and long presses still reach the buttons inside. Positions are raw
     * screen coordinates: the window moves under the finger, so view-local
     * ones would chase themselves.
     */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private inner class DragFrame(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var dragging = false

        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY
                    lastX = downX; lastY = downY
                    dragging = false
                }
                MotionEvent.ACTION_MOVE -> if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    lastX = ev.rawX; lastY = ev.rawY
                    onDragStart()
                }
            }
            return dragging
        }

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    onDrag(ev.rawX - lastX, ev.rawY - lastY)
                    lastX = ev.rawX; lastY = ev.rawY
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                    dragging = false
                    onDragEnd()
                }
            }
            return true
        }
    }

    /** Lifecycle + saved state for a Compose tree that has no Activity. */
    private class OverlayOwner : SavedStateRegistryOwner {
        private val registry = LifecycleRegistry(this)
        private val savedState = SavedStateRegistryController.create(this)

        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

        fun start() {
            savedState.performRestore(null as Bundle?)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }

        fun destroy() {
            registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        }
    }

    private companion object {
        const val K_RIGHT = "right"
        const val K_Y = "y"
    }
}
