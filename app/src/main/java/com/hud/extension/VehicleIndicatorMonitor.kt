package com.hud.extension

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Subscribes to turn-indicator vehicle signals:
 * - Vehicle.Body.Lights.IsLeftIndicatorOn
 * - Vehicle.Body.Lights.IsRightIndicatorOn
 *
 * Uses Harmony VehicleBodyManager / VehicleVendorExtensionManager via reflection when available.
 */
class VehicleIndicatorMonitor(
    private val onStateChanged: (leftOn: Boolean, rightOn: Boolean) -> Unit
) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var started = false
    private var leftOn = false
    private var rightOn = false

    private var managerClass: Class<*>? = null
    private var managerInstance: Any? = null
    private var callbackInstance: Any? = null
    private var zoneId: Int = 0

    fun start(context: Context): Boolean {
        if (started) return true
        val binding = bindVehicleManager(context) ?: return false

        managerClass = binding.managerClass
        managerInstance = binding.managerInstance
        callbackInstance = binding.callbackInstance
        zoneId = binding.zoneId

        val subscribed = subscribeSignal(VehicleIndicatorSignals.LEFT) &&
            subscribeSignal(VehicleIndicatorSignals.RIGHT)
        if (!subscribed) {
            stop()
            return false
        }

        readInitialState(VehicleIndicatorSignals.LEFT)
        readInitialState(VehicleIndicatorSignals.RIGHT)
        started = true
        HudLog.i("turn-signal vehicle monitor started")
        return true
    }

    fun stop() {
        if (!started && managerInstance == null) return
        unsubscribeAll()
        managerClass = null
        managerInstance = null
        callbackInstance = null
        started = false
        leftOn = false
        rightOn = false
    }

    private data class ManagerBinding(
        val managerClass: Class<*>,
        val managerInstance: Any,
        val callbackInstance: Any,
        val zoneId: Int
    )

    private fun bindVehicleManager(context: Context): ManagerBinding? {
        val managerCandidates = listOf(
            "com.huawei.automotive.vehicle.VehicleBodyManager",
            "com.huawei.vehicle.VehicleBodyManager",
            "com.huawei.vehiclecontrol.manager.VehicleBodyManager",
            "com.huawei.hmsauto.vehicle.VehicleBodyManager",
            "com.huawei.hosauto.vehicle.VehicleBodyManager",
            "com.huawei.automotive.vehicle.VehicleVendorExtensionManager",
            "com.huawei.vehicle.VehicleVendorExtensionManager",
        )

        for (className in managerCandidates) {
            val managerClass = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val instance = obtainManagerInstance(context, managerClass) ?: continue
            val callbackClass = findCallbackClass(managerClass) ?: continue
            val callback = createCallback(callbackClass) ?: continue
            val zone = resolveZoneNone()
            HudLog.i("turn-signal using vehicle manager $className")
            return ManagerBinding(managerClass, instance, callback, zone)
        }
        return null
    }

    private fun obtainManagerInstance(context: Context, managerClass: Class<*>): Any? {
        managerClass.methods.firstOrNull { method ->
            method.name == "getInstance" &&
                method.parameterCount == 1 &&
                Context::class.java.isAssignableFrom(method.parameterTypes[0])
        }?.let { method ->
            return runCatching { method.invoke(null, context.applicationContext) }.getOrNull()
        }

        managerClass.methods.firstOrNull { method ->
            method.name == "getInstance" && method.parameterCount == 0
        }?.let { method ->
            return runCatching { method.invoke(null) }.getOrNull()
        }

        return runCatching { managerClass.getDeclaredConstructor().newInstance() }.getOrNull()
    }

    private fun findCallbackClass(managerClass: Class<*>): Class<*>? {
        val nested = managerClass.declaredClasses.firstOrNull { type ->
            type.simpleName.contains("Callback", ignoreCase = true) ||
                type.simpleName.contains("Listener", ignoreCase = true)
        }
        if (nested != null) return nested

        val packagePrefix = managerClass.`package`?.name.orEmpty()
        val globalNames = listOf(
            "$packagePrefix.VehicleSignalCallback",
            "$packagePrefix.callback.VehicleSignalCallback",
            "com.huawei.automotive.vehicle.VehicleSignalCallback",
        )
        return globalNames.firstNotNullOfOrNull { name ->
            runCatching { Class.forName(name) }.getOrNull()
        }
    }

    private fun createCallback(callbackClass: Class<*>): Any? {
        val handler = InvocationHandler { _, method, args ->
            if (isSignalChangeMethod(method)) {
                val propId = args?.getOrNull(0) as? String
                val value = args?.getOrNull(2) ?: args?.getOrNull(1)
                if (propId != null) {
                    dispatchSignal(propId, value)
                }
            }
            null
        }
        return Proxy.newProxyInstance(callbackClass.classLoader, arrayOf(callbackClass), handler)
    }

    private fun isSignalChangeMethod(method: Method): Boolean {
        if (method.parameterCount !in 2..3) return false
        return method.name.contains("Signal", ignoreCase = true) ||
            method.name.contains("Change", ignoreCase = true) ||
            method.name == "onCallback"
    }

    private fun subscribeSignal(propId: String): Boolean {
        val manager = managerInstance ?: return false
        val callback = callbackInstance ?: return false
        val managerClass = managerClass ?: return false

        val method = managerClass.methods.firstOrNull { method ->
            method.name == "subscribeVehicleSignal" && method.parameterCount == 3
        } ?: return false

        return runCatching {
            method.invoke(manager, propId, zoneId, callback)
            true
        }.getOrElse { error ->
            HudLog.e("turn-signal subscribe failed for $propId", error)
            false
        }
    }

    private fun readInitialState(propId: String) {
        val manager = managerInstance ?: return
        val managerClass = managerClass ?: return
        val method = managerClass.methods.firstOrNull { method ->
            method.name == "getVehicleSignal" && method.parameterCount == 3
        } ?: return

        val value = runCatching {
            method.invoke(manager, Boolean::class.javaPrimitiveType, propId, zoneId)
        }.getOrNull() ?: runCatching {
            method.invoke(manager, java.lang.Boolean::class.java, propId, zoneId)
        }.getOrNull()

        dispatchSignal(propId, value)
    }

    private fun unsubscribeAll() {
        val manager = managerInstance ?: return
        val managerClass = managerClass ?: return
        val method = managerClass.methods.firstOrNull { it.name == "unsubscribeVBodySignalAll" }
            ?: managerClass.methods.firstOrNull { it.name == "unsubscribeVehicleSignalAll" }
            ?: return
        runCatching { method.invoke(manager) }
    }

    private fun resolveZoneNone(): Int {
        val zoneClasses = listOf(
            "com.huawei.automotive.vehicle.VehicleZone",
            "com.huawei.vehicle.VehicleZone",
            "com.huawei.vehiclecontrol.manager.VehicleZone",
        )
        for (className in zoneClasses) {
            val zoneClass = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val field = zoneClass.fields.firstOrNull { it.name == "ZONE_NONE" } ?: continue
            return runCatching { field.getInt(null) }.getOrElse { 0 }
        }
        return 0
    }

    private fun dispatchSignal(propId: String, rawValue: Any?) {
        val on = parseOn(rawValue)
        mainHandler.post {
            when (propId) {
                VehicleIndicatorSignals.LEFT -> leftOn = on
                VehicleIndicatorSignals.RIGHT -> rightOn = on
                else -> return@post
            }
            onStateChanged(leftOn, rightOn)
        }
    }

    private fun parseOn(rawValue: Any?): Boolean = when (rawValue) {
        null -> false
        is Boolean -> rawValue
        is Number -> rawValue.toInt() != 0
        is String -> rawValue.equals("true", ignoreCase = true) ||
            rawValue == "1" ||
            rawValue.equals("on", ignoreCase = true)
        else -> rawValue.toString().equals("true", ignoreCase = true)
    }
}

object VehicleIndicatorSignals {
    const val LEFT = "Vehicle.Body.Lights.IsLeftIndicatorOn"
    const val RIGHT = "Vehicle.Body.Lights.IsRightIndicatorOn"
}
