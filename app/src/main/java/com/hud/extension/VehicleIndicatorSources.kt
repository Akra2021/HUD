package com.hud.extension

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

internal interface VehicleIndicatorSource {
    val name: String
    fun start(context: Context, onStateChanged: (leftOn: Boolean, rightOn: Boolean) -> Unit): Boolean
    fun stop()
}

/** Harmony VehicleBodyManager via reflection (works only on whitelisted / SDK-linked apps). */
internal class ReflectionVehicleSource : VehicleIndicatorSource {
    override val name = "reflection-api"
    private var delegate: VehicleIndicatorMonitor? = null

    override fun start(
        context: Context,
        onStateChanged: (Boolean, Boolean) -> Unit
    ): Boolean {
        val monitor = VehicleIndicatorMonitor(onStateChanged)
        if (!monitor.start(context)) return false
        delegate = monitor
        return true
    }

    override fun stop() {
        delegate?.stop()
        delegate = null
    }
}

/**
 * Polls recent logcat for Vehicle.Body.Lights.IsLeft/RightIndicatorOn.
 * Works on some Harmony HUs where signals are logged but API is not exposed to APKs.
 */
internal class ShellLogcatVehicleSource : VehicleIndicatorSource {
    override val name = "shell-logcat"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ((Boolean, Boolean) -> Unit)? = null
    private var lastLeft = false
    private var lastRight = false
    private var polling = false
    private var pollsWithoutSignal = 0

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollOnce()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun start(
        context: Context,
        onStateChanged: (Boolean, Boolean) -> Unit
    ): Boolean {
        if (!canReadLogcat()) return false
        callback = onStateChanged
        polling = true
        pollsWithoutSignal = 0
        mainHandler.post(pollRunnable)
        HudLog.i("turn-signal logcat polling started")
        return true
    }

    override fun stop() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        callback = null
        lastLeft = false
        lastRight = false
        pollsWithoutSignal = 0
    }

    private fun canReadLogcat(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "logcat -d -t 3 2>/dev/null"))
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun pollOnce() {
        val text = readRecentLogcat()
        val parsed = TurnSignalLogParser.parseWithPresence(text)
        if (parsed == null) {
            pollsWithoutSignal++
            if (pollsWithoutSignal == NO_SIGNAL_WARN_POLLS) {
                HudLog.w("turn-signal logcat: no VHAL indicator lines — check logcat access")
            }
            return
        }
        pollsWithoutSignal = 0
        val state = parsed.mergeWithPrevious(lastLeft, lastRight)
        if (state.leftOn == lastLeft && state.rightOn == lastRight) return
        lastLeft = state.leftOn
        lastRight = state.rightOn
        callback?.invoke(state.leftOn, state.rightOn)
    }

    private fun readRecentLogcat(): String = runCatching {
        val process = Runtime.getRuntime().exec(
            arrayOf(
                "sh",
                "-c",
                "logcat -d -t 300 2>/dev/null | grep -iE " +
                    "'IsLeftIndicatorOn|IsRightIndicatorOn|" +
                    "Vehicle\\.Body\\.Lights|" +
                    "TurnLeftLightListener|TurnRightLightListener|" +
                    "turnLeftLight|turnRightLight'"
            )
        )
        process.inputStream.bufferedReader().readText()
    }.getOrElse { "" }

    companion object {
        private const val POLL_MS = 300L
        private const val NO_SIGNAL_WARN_POLLS = 100
    }
}

/** Polls dumpsys output for indicator property lines. */
internal class ShellDumpsysVehicleSource : VehicleIndicatorSource {
    override val name = "shell-dumpsys"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var callback: ((Boolean, Boolean) -> Unit)? = null
    private var lastLeft = false
    private var lastRight = false
    private var polling = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!polling) return
            pollOnce()
            mainHandler.postDelayed(this, POLL_MS)
        }
    }

    override fun start(
        context: Context,
        onStateChanged: (Boolean, Boolean) -> Unit
    ): Boolean {
        if (!canReadDumpsys()) return false
        callback = onStateChanged
        polling = true
        mainHandler.post(pollRunnable)
        HudLog.i("turn-signal dumpsys polling started")
        return true
    }

    override fun stop() {
        polling = false
        mainHandler.removeCallbacks(pollRunnable)
        callback = null
    }

    private fun canReadDumpsys(): Boolean = runCatching {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "dumpsys activity 2>/dev/null | head -1"))
        process.waitFor() == 0
    }.getOrDefault(false)

    private fun pollOnce() {
        val text = readDumpsysSnippet()
        val parsed = TurnSignalLogParser.parseWithPresence(text) ?: return
        val state = parsed.mergeWithPrevious(lastLeft, lastRight)
        if (state.leftOn == lastLeft && state.rightOn == lastRight) return
        lastLeft = state.leftOn
        lastRight = state.rightOn
        callback?.invoke(state.leftOn, state.rightOn)
    }

    private fun readDumpsysSnippet(): String = runCatching {
        val commands = listOf(
            "dumpsys 2>/dev/null | grep -iE 'IndicatorOn|TURN_SIGNAL' | tail -30",
            "dumpsys vehicle 2>/dev/null | tail -80",
            "dumpsys activity services 2>/dev/null | grep -iE 'Indicator|vehicle' | tail -30",
        )
        buildString {
            for (cmd in commands) {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
                append(process.inputStream.bufferedReader().readText())
            }
        }
    }.getOrElse { "" }

    companion object {
        private const val POLL_MS = 400L
    }
}

/** Listens for vehicle-related broadcasts (OEM-specific). */
internal class BroadcastVehicleSource : VehicleIndicatorSource {
    override val name = "broadcast"

    private var receiver: BroadcastReceiver? = null
    private var appContext: Context? = null

    override fun start(
        context: Context,
        onStateChanged: (Boolean, Boolean) -> Unit
    ): Boolean {
        val filter = IntentFilter().apply {
            addAction(ACTION_LEFT)
            addAction(ACTION_RIGHT)
            addAction(ACTION_TURN_SIGNAL)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                intent ?: return
                val left = intent.getBooleanExtra(EXTRA_LEFT, false) ||
                    intent.action == ACTION_LEFT ||
                    intent.getStringExtra(EXTRA_SIDE).equals("left", ignoreCase = true)
                val right = intent.getBooleanExtra(EXTRA_RIGHT, false) ||
                    intent.action == ACTION_RIGHT ||
                    intent.getStringExtra(EXTRA_SIDE).equals("right", ignoreCase = true)
                onStateChanged(left, right)
            }
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(rcv, filter, Context.RECEIVER_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                context.applicationContext.registerReceiver(rcv, filter)
            }
            receiver = rcv
            appContext = context.applicationContext
            HudLog.i("turn-signal broadcast listener registered")
            true
        }.getOrElse {
            HudLog.w("turn-signal broadcast listener failed: ${it.message}")
            false
        }
    }

    override fun stop() {
        val ctx = appContext ?: return
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        appContext = null
    }

    companion object {
        private const val ACTION_LEFT = "com.huawei.vehicle.TURN_SIGNAL_LEFT"
        private const val ACTION_RIGHT = "com.huawei.vehicle.TURN_SIGNAL_RIGHT"
        private const val ACTION_TURN_SIGNAL = "com.huawei.vehicle.TURN_SIGNAL_CHANGED"
        private const val EXTRA_LEFT = "left"
        private const val EXTRA_RIGHT = "right"
        private const val EXTRA_SIDE = "side"
    }
}

/** Manual trigger for testing when no vehicle source is available. */
internal class ManualVehicleSource : VehicleIndicatorSource {
    override val name = "manual"

    private var callback: ((Boolean, Boolean) -> Unit)? = null
    private var leftOn = false
    private var rightOn = false

    override fun start(
        context: Context,
        onStateChanged: (Boolean, Boolean) -> Unit
    ): Boolean {
        callback = onStateChanged
        return true
    }

    override fun stop() {
        callback = null
        leftOn = false
        rightOn = false
    }

    fun setLeft(on: Boolean) {
        leftOn = on
        if (on) rightOn = false
        callback?.invoke(leftOn, rightOn)
    }

    fun setRight(on: Boolean) {
        rightOn = on
        if (on) leftOn = false
        callback?.invoke(leftOn, rightOn)
    }

    fun clear() {
        leftOn = false
        rightOn = false
        callback?.invoke(false, false)
    }
}
