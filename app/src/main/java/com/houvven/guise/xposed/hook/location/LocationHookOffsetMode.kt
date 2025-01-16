package com.houvven.guise.xposed.hook.location

import android.app.AndroidAppHelper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.guise.xposed.hook.location.CoordTransform.LatLng.Companion.relative360BearingDelta
import com.houvven.ktx_xposed.hook.*
import com.houvven.ktx_xposed.logger.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt


class LocationHookOffsetMode(override val config: ModuleConfig) : LocationHookBase(config) {
    companion object {
        private const val LOCATION_EXPIRED_TIME_MS = 5 * 60 * 1000L       // 5min
        private const val LOCATION_LAST_GCJ02_EXPIRED_MS = 2 * 60 * 1000L // 2min
        private const val LOCATION_LATEST_PURES_EXPIRED_MS = 2 * 60 * 1000L // 2min
        private const val LOCATION_LAST_GCJ02_CACHING_MS = 1 * 60 * 1000L // 1min

        private const val LOCATION_READ_ONLY = true
        private const val PASSIVE_LOCATION_HOOK = true
        private const val PASSIVE_LOCATION_ALWAYS_AS_GCJ02 = false
        private const val PASSIVE_LOCATION_FALLBACK_AS_WGS84_OR_GCJ02 = true
        private const val LOCATION_MOVE_DIRECTION_TOLERANCE = 5  // 5°(0~360)
        private const val LOCATION_MOVE_SPEED_TOLERANCE = 10     // 10mps
        private const val LOCATION_MOVE_DISTANCE_TOLERANCE = 20  // 20m
    }

    private val initMs = System.currentTimeMillis()
    private val locker by lazy { this }
    private val simpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    // Last Location(gcj02)
    private var lastGcj02LatLng: CoordTransform.LatLng? = null
        // set(value) {
        //     logcatWarn { "updateLastLatLng: [${field?.latitude}, ${field?.longitude}] >> [${value?.latitude},${value?.longitude}]" }
        //     field = value
        // }

    // Pure Location(wgs84, gcj02) for deferring fused location type
    private var latestPureLocation: Pair<CoordTransform.LatLng, CoordTransform.LatLng>? = null
        // set(value) {
        //     logcatWarn { "latestPureLocation: [${field?.first?.latitude}, ${field?.first?.longitude}] >> [${value?.first?.latitude},${value?.first?.longitude}]" }
        //     field = value
        // }

    private val listenerHolder: MutableMap<Int, LocationListener> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { hashMapOf() }

    override fun start() {
        logcatInfo { "#start" }

        // Location APIs
        hookLocation()
        hookGetLastLocation()
        hookLocationUpdateRequest()
        hookLocationListener()
        // hookILocationListenerOfSystemService()

        // Providers
        hookProviderState()

        // GPS
        // removeNmeaListener()
        // hookGnssStatus()
        // hookGpsStatus()
        // hookGpsStatusListener()

        logcatInfo { "#start done" }
    }

    private fun hookLocation() {
        if (!PASSIVE_LOCATION_HOOK) {
            return
        }
        logcatInfo { "hookLocation" }
        Location::class.java.run {
            for (method in declaredMethods) {
                var hasHook = false
                if ((method.name == "getLatitude" || method.name == "getLongitude") && method.returnType == Double::class.java) {
                    hasHook = true
                    val reentrantGuard = ThreadLocal<Boolean>().also { it.set(false) }
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        if (reentrantGuard.get() == true) {
                            logcat {
                                error(">>>>>>>>>>>>>>>>>>")
                                error("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${method.name}")
                                error("\treentrant: skip")
                                error("<<<<<<<<<<<<<<<<<<")
                            }
                            return@afterHookedMethod
                        }
                        var hasConsumed = false
                        try {
                            reentrantGuard.set(true)
                            synchronized(locker) {
                                (hookParam.thisObject as? Location)?.let { location ->
                                    var lastGcj02: CoordTransform.LatLng? = null
                                    var latestPures: Pair<CoordTransform.LatLng, CoordTransform.LatLng>? = null
                                    val provider = location.safeGetProvider()
                                    val old = hookParam.result

                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${method.name}@${location.myHashcode()}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("\ttime: ${simpleDateFormat.format(location.safeGetTime())}, expired=${location.isExpired(lastGcj02)}")
                                        info("\tmotion: speed=${location.safeHasSpeed()}(${location.safeGetSpeed()}), bearing=${location.safeHasBearing()}(${location.safeGetBearing()})")
                                        info("\tprovider: $provider")
                                        info("\t$location")
                                    }

                                    val onRely = { mode: String, currGcj02: CoordTransform.LatLng? ->
                                        hasConsumed = true
                                        if (currGcj02 != null) {
                                            // Should we update reliable location as last gcj-02?
                                            updateLastGcj02LatLng(currGcj02, "hookLocation#${method.name}($provider)-$mode")
                                        }
                                        logcat {
                                            info("onRely")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: $lastGcj02")
                                            info("\tlatest-pure: ${latestPures?.first}, ${latestPures?.second}")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }
                                    val onTransForm = { mode: String, currGcj02: CoordTransform.LatLng? ->
                                        hasConsumed = true
                                        if (currGcj02 != null) {
                                            updateLastGcj02LatLng(currGcj02, "hookLocation#${method.name}($provider)-$mode")
                                        }
                                        logcat {
                                            info("onTransForm")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: $lastGcj02")
                                            info("\tlatest-pure: ${latestPures?.first}, ${latestPures?.second}")
                                            info("\t${method.name} ${if (old == hookParam.result) "==" else ">>"}: $old to ${hookParam.result}")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }
                                    val onDrop = { mode: String ->
                                        hasConsumed = true
                                        lastGcj02?.let {
                                            noteLatLngMoving(it, "hookLocation#${method.name}($provider)-$mode")
                                        }
                                        logcat {
                                            info("onDrop")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: $lastGcj02")
                                            info("\tlatest-pure: ${latestPures?.first}, ${latestPures?.second}")
                                            info("\t${method.name} ${if (old == hookParam.result) "==" else ">>"}: $old to ${hookParam.result}")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }

                                    // Handle passive location:
                                    //  1. passive-location will be effected by transforming of up-stream locations
                                    //  2. there are 3 states that the current passive-location may be:
                                    //    - transformable(wgs-84), which should be transformed into gcj-02
                                    //    - transformed(gcj-02), which we rely it and do nothing
                                    //    - twice-transformed(gcj-02^2), which we reserve it into gcj-02
                                    //  3. here we try to tell which state of this location by:
                                    //    - provider & extras info
                                    //    - latest pure location: infer the most nearly one
                                    //    - last gcj-02 location: infer the most nearly one

                                    synchronized(location) {
                                        // 1. Out of bounds
                                        if (!location.shouldTransform()) {
                                            onRely("rely-out-of-bounded", null)
                                            return@afterHookedMethod
                                        }

                                        val lastLatLng = getLatGcj02LatLng().also { lastGcj02 = it }

                                        // 2. Expired
                                        if (location.isExpired(lastLatLng)) {
                                            onRely("rely-expired", null)
                                            return@afterHookedMethod
                                        }
                                        // 3. Has been transformed into wgs-84
                                        if (location.isGcj02Location()) {
                                            onRely("rely-gcj02", location.safeGetLatLng())
                                            return@afterHookedMethod
                                        }

                                        // 4. Check if transformable
                                        if (location.isTransformable(true)) {
                                            val pair = location.wgs84ToGcj02(LOCATION_READ_ONLY)?.also { (_, gcj02LatLng) ->
                                                when (method.name) {
                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                }
                                            }
                                            onTransForm("trans-transform", pair?.second)
                                            return@afterHookedMethod
                                        }

                                        if (PASSIVE_LOCATION_ALWAYS_AS_GCJ02) {
                                            onRely("rely-always", location.safeGetLatLng())
                                            return@afterHookedMethod
                                        }

                                        val currMs = location.safeGetTime()
                                        val currLatLng = location.safeGetLatLng()

                                        // 5. Compare to latest pure location
                                        if (location.isReliableFused(lastLatLng)) {
                                            val latestPureLocation = getLatestPureLatLng().also { latestPures = it }
                                            if (currLatLng == null || latestPureLocation == null || latestPureLocation.first.isExpired(LOCATION_LATEST_PURES_EXPIRED_MS, currMs)) {
                                                logcatWarn { "\tcompareToPure: skip, currLatLng=$currLatLng, latestPureLocation=$latestPureLocation, expired=${latestPureLocation?.first?.isExpired(LOCATION_LATEST_PURES_EXPIRED_MS, currMs)}" }

                                            } else {
                                                // Compare speed & bearing
                                                run {
                                                    val speedFromWgs84Mps = currLatLng.speedMpsFrom(latestPureLocation.first)
                                                    val speedFromGcj02Mps = currLatLng.speedMpsFrom(latestPureLocation.second)
                                                    logcatInfo { "\tspeedFromPure: wgs84=$speedFromWgs84Mps, gcj02=$speedFromGcj02Mps" }

                                                    if (speedFromWgs84Mps == null || speedFromGcj02Mps == null) {
                                                        return@run
                                                    }

                                                    val speedTolerance = LOCATION_MOVE_SPEED_TOLERANCE // tolerance(meterPerSec)
                                                    val speedFromWgs84Delta = abs(speedFromWgs84Mps - latestPureLocation.first.speed)
                                                    val speedFromGcj02Delta = abs(speedFromGcj02Mps - latestPureLocation.second.speed)

                                                    if (latestPureLocation.first.hasSpeedAndBearing && latestPureLocation.second.hasSpeedAndBearing) {
                                                        val bearingFromWgs84 = latestPureLocation.first.bearingToIn360Degree(location)
                                                        val bearingFromGcj02 = latestPureLocation.second.bearingToIn360Degree(location)
                                                        logcatInfo { "\tbearingFromPure: wgs84=$bearingFromWgs84, gcj02=$bearingFromGcj02" }

                                                        val bearingTolerance = LOCATION_MOVE_DIRECTION_TOLERANCE // degree(0~360)
                                                        val bearingFromWgs84Delta = relative360BearingDelta(bearingFromWgs84, latestPureLocation.first.bearing)
                                                        logcatInfo { "\twgs84Delta: speedDelta=$speedFromWgs84Delta, bearingDelta=$bearingFromWgs84Delta" }

                                                        if (speedFromWgs84Delta <= speedTolerance && bearingFromWgs84Delta <= bearingTolerance) {
                                                            val pair = location.wgs84ToGcj02(LOCATION_READ_ONLY)?.also { (_, gcj02LatLng) ->
                                                                when (method.name) {
                                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                                }
                                                            }
                                                            onTransForm("trans-pure-bearing-wsj84", pair?.second)
                                                            return@afterHookedMethod
                                                        }

                                                        val bearingFromGcj02Delta = relative360BearingDelta(bearingFromGcj02, latestPureLocation.second.bearing)
                                                        logcatInfo { "\tgcj02Delta: speedDelta=$speedFromGcj02Delta, bearingDelta=$bearingFromGcj02Delta" }

                                                        if (speedFromGcj02Delta <= speedTolerance && bearingFromGcj02Delta <= bearingTolerance) {
                                                            onRely("rely-pure-bearing-gcj02", currLatLng)
                                                            return@afterHookedMethod
                                                        }
                                                    }

                                                    // Compare speed
                                                    logcatInfo { "\tspeedFromPureDelta: wgs84=$speedFromWgs84Delta, gcj02=$speedFromGcj02Delta" }
                                                    if (speedFromWgs84Delta <= speedTolerance || speedFromGcj02Delta <= speedTolerance) {
                                                        if (speedFromWgs84Delta < speedFromGcj02Delta) {
                                                            val pair = location.wgs84ToGcj02(LOCATION_READ_ONLY)?.also { (_, gcj02LatLng) ->
                                                                when (method.name) {
                                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                                }
                                                            }
                                                            onTransForm("trans-pure-speed-wsj84", pair?.second)
                                                        } else {
                                                            onRely("rely-pure-speed-gcj02", currLatLng)
                                                        }
                                                        return@afterHookedMethod
                                                    }
                                                }

                                                // Compare distance
                                                run {
                                                    val tolerance = LOCATION_MOVE_DISTANCE_TOLERANCE // tolerance(meter)
                                                    val distanceToWgs84 = currLatLng.toDistance(latestPureLocation.first)
                                                    val distanceToGcj02 = currLatLng.toDistance(latestPureLocation.second)
                                                    logcatInfo { "\tdistanceToPure: wgs84=$distanceToWgs84, gcj02=$distanceToGcj02" }
                                                    if (abs(distanceToWgs84) <= tolerance || abs(distanceToGcj02) <= tolerance) {
                                                        if (abs(distanceToWgs84) < abs(distanceToGcj02)) {
                                                            val pair = location.wgs84ToGcj02(LOCATION_READ_ONLY)?.also { (_, gcj02LatLng) ->
                                                                when (method.name) {
                                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                                }
                                                            }
                                                            onTransForm("trans-pure-distance-wsj84", pair?.second)
                                                        } else {
                                                            onRely("rely-pure-distance-gcj02", currLatLng)
                                                        }
                                                        return@afterHookedMethod
                                                    }
                                                }

                                                // Check if twice-transformed
                                                run {
                                                    logcat {
                                                        currLatLng.let {
                                                            CoordTransform.wgs84ToGcj02(latestPureLocation.second)?.let { gcj02TwiceLatLng ->
                                                                val distanceToGcj02Twice = it.toDistance(gcj02TwiceLatLng)
                                                                info("\tdistanceToPure: gcj02Twice=$distanceToGcj02Twice")
                                                            }
                                                        }
                                                    }
                                                    val tolerance = LOCATION_MOVE_DISTANCE_TOLERANCE // tolerance(meter)
                                                    location.tryReverseTransform(latestPureLocation.second, tolerance)?.let {
                                                        if (!LOCATION_READ_ONLY) {
                                                            location.markAsGcj02(it)
                                                        }
                                                        when (method.name) {
                                                            "getLatitude" -> hookParam.result = it.latitude
                                                            "getLongitude" -> hookParam.result = it.longitude
                                                        }
                                                        onTransForm("trans-pure-reverse", it)
                                                        return@afterHookedMethod
                                                    }
                                                }
                                            }
                                        }

                                        // 6. Compare to last gjc-02
                                        run {
                                            if (lastLatLng != null && !lastLatLng.isExpired(LOCATION_LAST_GCJ02_EXPIRED_MS, currMs)) {
                                                currLatLng?.let { currLatLng ->
                                                    // Compare speed & bearing
                                                    run {
                                                        val speedFromGcj02Mps = currLatLng.speedMpsFrom(lastLatLng)
                                                        logcatInfo { "\tspeedFromLast: gcj02=$speedFromGcj02Mps" }

                                                        if (speedFromGcj02Mps == null) {
                                                            return@run
                                                        }

                                                        val speedTolerance = LOCATION_MOVE_SPEED_TOLERANCE // tolerance(meterPerSec)
                                                        val speedFromGcj02Delta = abs(speedFromGcj02Mps - lastLatLng.speed)

                                                        if (lastLatLng.hasSpeedAndBearing) {
                                                            val bearingFromGcj02 = lastLatLng.bearingToIn360Degree(location)
                                                            logcatInfo { "\tbearingFromLast: gcj02=$bearingFromGcj02" }

                                                            val bearingTolerance = LOCATION_MOVE_DIRECTION_TOLERANCE // degree(0~360)
                                                            val bearingFromGcj02Delta = relative360BearingDelta(bearingFromGcj02, lastLatLng.bearing)
                                                            logcatInfo { "\tgcj02Delta: speedDelta=$speedFromGcj02Delta, bearingDelta=$bearingFromGcj02Delta" }

                                                            if (speedFromGcj02Delta <= speedTolerance && bearingFromGcj02Delta <= bearingTolerance) {
                                                                onRely("rely-last-bearing", currLatLng)
                                                                return@afterHookedMethod
                                                            }
                                                        }

                                                        // Compare speed
                                                        logcatInfo { "\tspeedFromLastDelta: gcj02=$speedFromGcj02Delta" }
                                                        if (speedFromGcj02Delta <= speedTolerance) {
                                                            onRely("rely-last-speed", currLatLng)
                                                            return@afterHookedMethod
                                                        }
                                                    }

                                                    // Compare distance
                                                    val distanceToGcj02 = currLatLng.toDistance(lastLatLng)
                                                    logcatInfo { "\tdistanceToLast: gcj02=$distanceToGcj02" }
                                                    if (abs(distanceToGcj02) <= LOCATION_MOVE_DISTANCE_TOLERANCE) {
                                                        onRely("rely-last-distance", currLatLng)
                                                        return@afterHookedMethod
                                                    }
                                                }

                                                // Check if twice-transformed
                                                run {
                                                    val tolerance = LOCATION_MOVE_DISTANCE_TOLERANCE // tolerance(meter)
                                                    val reversedLatLng = location.tryReverseTransform(lastLatLng, tolerance)
                                                    reversedLatLng?.let {
                                                        if (!LOCATION_READ_ONLY) {
                                                            location.markAsGcj02(it)
                                                        }
                                                        when (method.name) {
                                                            "getLatitude" -> hookParam.result = it.latitude
                                                            "getLongitude" -> hookParam.result = it.longitude
                                                        }
                                                        onTransForm("trans-last-reverse", it)
                                                        return@afterHookedMethod
                                                    }
                                                }
                                            }
                                        }

                                        // 7. Caching by last gjc-02
                                        run {
                                            // Try pass by the last gcj-02 location as cache
                                            if (lastLatLng != null && !lastLatLng.isExpired(LOCATION_LAST_GCJ02_CACHING_MS, currMs)) {
                                                val mode = "drop-cache"
                                                lastLatLng.let {
                                                    if (!LOCATION_READ_ONLY) {
                                                        location.markAsGcj02(it)
                                                    }
                                                    when (method.name) {
                                                        "getLatitude" -> hookParam.result = it.latitude
                                                        "getLongitude" -> hookParam.result = it.longitude
                                                    }
                                                }
                                                onDrop(mode)
                                                return@afterHookedMethod
                                            }
                                        }

                                        // *. WTF states
                                        logcatWarn { "\ttime ago: $currMs - ${lastGcj02LatLng?.timeMs} = ${TimeUnit.MILLISECONDS.toSeconds((currMs - (lastGcj02LatLng?.timeMs ?: 0)))}s" }
                                        if (PASSIVE_LOCATION_FALLBACK_AS_WGS84_OR_GCJ02) {
                                            val pair = location.wgs84ToGcj02(LOCATION_READ_ONLY)?.also { (_, gcj02LatLng) ->
                                                when (method.name) {
                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                }
                                            }
                                            onTransForm("trans-unknown", pair?.second)
                                        } else {
                                            onRely("rely-unknown", location.safeGetLatLng())
                                        }
                                    }
                                }
                            }
                        } finally {
                            reentrantGuard.set(false)
                            if (!hasConsumed) {
                                exit { IllegalStateException("Location not consumed, review the code branches above!") }
                            }
                        }
                    }
                }

                // Hook the remaining apis for debug
                if (debuggable() && !hasHook) {
                    if (method.name.startsWith("set")) {
                        val target = method.name
                        val paramsTypes = method.parameterTypes
                        afterHookedMethod(target, *paramsTypes) { hookParam ->
                            synchronized(locker) {
                                logcat {
                                    val hashcode = (hookParam.thisObject as? Location)?.myHashcode() ?: "null"
                                    info("onMethodInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}@${hashcode}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                    // info("\tfrom:")
                                    // Throwable().stackTrace.forEach {
                                    //     info("\t\t$it")
                                    // }
                                }
                            }
                        }
                    }
                }
            }

            // Hook constructors for debug
            if (debuggable()) {
                try {
                    logcatWarn { "Hook constructors" }
                    XposedHelpers.findAndHookConstructor(
                        this,
                        String::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(hookParam: MethodHookParam) {
                                logcat {
                                    info("onConstructorInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                }
                            }
                        })

                    XposedHelpers.findAndHookConstructor(
                        this,
                        Location::class.java,
                        object : XC_MethodHook() {
                            override fun afterHookedMethod(hookParam: MethodHookParam) {
                                logcat {
                                    info("onConstructorInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                }
                            }
                        }
                    )
                } catch (e: Throwable) {
                    exit { IllegalStateException("Hook constructors error", e) }
                } finally {
                    logcatWarn { "Hook constructors done" }
                }
            }
        }
    }

    private fun hookGetLastLocation() {
        logcatInfo { "hookGetLastLocation" }
        LocationManager::class.java.run {
            declaredMethods.filter {
                (it.name == "getLastLocation" || it.name == "getLastKnownLocation") && it.returnType == Location::class.java
            }.forEach { method ->
                afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                    synchronized(locker) {
                        (hookParam.result as? Location)?.let { location ->
                            logcat {
                                info(">>>>>>>>>>>>>>>>>>")
                                info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                info("\tfrom:")
                                Throwable().stackTrace.forEach {
                                    info("\t\t$it")
                                }
                                info("\ttime: ${simpleDateFormat.format(location.safeGetTime())}, expired=${location.isExpired(lastGcj02LatLng)}")
                                info("\t$location")
                                info("\tprovider: ${location.safeGetProvider()}")
                                info("<<<<<<<<<<<<<<<<<<")
                            }
                            if (!location.isExpired(lastGcj02LatLng)) {
                                hookParam.result = modifyLocationToGcj02(
                                    location,
                                    "hookGetLastLocation-${method.name}",
                                    keepAsLastLatLng = true,
                                    keepAsLatestPureLocation = false
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookLocationUpdateRequest() {
        logcatInfo { "hookLocationUpdateRequest" }

        val requestLocationUpdates = "requestLocationUpdates"
        val requestSingleUpdate = "requestSingleUpdate"
        val removeUpdates = "removeUpdates"

        LocationManager::class.java.run {
            for (method in declaredMethods) {
                var hasHook = false
                if (!HOOK_LOCATION_LISTENER &&
                    (method.name == requestLocationUpdates || method.name == requestSingleUpdate || method.name == removeUpdates)
                ) {
                    val target = method.name
                    val paramsTypes = method.parameterTypes
                    val indexOf = method.parameterTypes.indexOf(LocationListener::class.java)
                    if (indexOf == -1) {
                        // Just intercept invocation right now
                        // log("replace: $target($paramsTypes)")
                        // replaceMethod(this, target, *paramsTypes) {
                        //     log("replaceMethod $target $paramsTypes")
                        // }
                        continue
                    } else {
                        // Hook and modify location
                        hasHook = true
                        afterHookedMethod(target, *paramsTypes) { hookParam ->
                            synchronized(locker) {
                                val listener = hookParam.args[indexOf] as LocationListener
                                logcat {
                                    info(">>>>>>>>>>>>>>>>>>")
                                    info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#$target, idx=$indexOf, listener=${listener.hashCode()}")
                                    info("\tfrom:")
                                    Throwable().stackTrace.forEach {
                                        info("\t\t$it")
                                    }
                                    info("<<<<<<<<<<<<<<<<<<")
                                }
                                when(method.name) {
                                    requestLocationUpdates, requestSingleUpdate -> {
                                        val wrapper = listenerHolder.getOrElse(listener.hashCode()) {
                                            return@getOrElse object : LocationListener {
                                                override fun onLocationChanged(location: Location) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onLocationChanged" }
                                                        listener.onLocationChanged(modifyLocationToGcj02(location, "hookLocationUpdateRequest-${method.name}"))
                                                    }
                                                }
                                                override fun onLocationChanged(locations: List<Location>) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onLocationChanged list: ${locations.size}" }
                                                        val fakeLocations = arrayListOf<Location>()
                                                        for (location in locations) {
                                                            fakeLocations.add(modifyLocationToGcj02(location, "hookLocationUpdateRequest-${method.name}"))
                                                        }
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                            listener.onLocationChanged(fakeLocations)
                                                        }
                                                    }
                                                }
                                                override fun onFlushComplete(requestCode: Int) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onFlushComplete" }
                                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                            listener.onFlushComplete(requestCode)
                                                        }
                                                    }
                                                }
                                                override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onStatusChanged: provider=$provider, status=$status" }
                                                        listener.onStatusChanged(provider, status, extras)
                                                    }
                                                }
                                                override fun onProviderEnabled(provider: String) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onProviderEnabled" }
                                                        listener.onProviderEnabled(provider)
                                                    }
                                                }
                                                override fun onProviderDisabled(provider: String) {
                                                    synchronized(locker) {
                                                        logcatInfo { "onProviderDisabled" }
                                                        listener.onProviderDisabled(provider)
                                                    }
                                                }
                                            }
                                        }
                                        if (method.name != requestSingleUpdate) {
                                            synchronized(listenerHolder) {
                                                listenerHolder[listener.hashCode()] = wrapper
                                            }
                                        }
                                        logcatInfo { "\tadd origin(${listener.hashCode()})>>wrapper(${wrapper.hashCode()}), size=${listenerHolder.size}" }
                                        hookParam.args[indexOf] = wrapper
                                    }

                                    removeUpdates -> {
                                        synchronized(listenerHolder) {
                                            logcatInfo { "\tlistenerHolder: $listenerHolder" }
                                            listenerHolder[listener.hashCode()]?.let {
                                                hookParam.args[indexOf] = it
                                            }
                                            val hashcodeToBeRemoved = mutableListOf<Int>()
                                            var next = listener.hashCode()
                                            while (listenerHolder.containsKey(next)) {
                                                hashcodeToBeRemoved.add(next)
                                                next = listenerHolder[next].hashCode()
                                            }
                                            hashcodeToBeRemoved.forEach { hashcode ->
                                                listenerHolder.remove(hashcode)?.let { wrapper ->
                                                    logcatInfo { "\tremove origin($hashcode)>>wrapper(${wrapper.hashCode()}), size=${listenerHolder.size}" }
                                                    if (hashcode != listener.hashCode()) {
                                                        XposedBridge.invokeOriginalMethod(
                                                            hookParam.method,
                                                            hookParam.thisObject,
                                                            arrayOf(wrapper)
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Hook the remaining apis for debug
                if (debuggable() && !hasHook) {
                    val target = method.name
                    val paramsTypes = method.parameterTypes
                    afterHookedMethod(target, *paramsTypes) { hookParam ->
                        synchronized(locker) {
                            logcat {
                                info("onMethodInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                // info("\tfrom:")
                                // Throwable().stackTrace.forEach {
                                //     info("\t\t$it")
                                // }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookLocationListener() {
        if (!HOOK_LOCATION_LISTENER) {
            return
        }

        logcatInfo { "hookLocationListener" }

        val onLocationChanged = "onLocationChanged"
        LocationListener::class.java.run {
            for (method in declaredMethods) {
                val target = method.name
                val paramsTypes = method.parameterTypes
                if (target == onLocationChanged) {
                    if (paramsTypes.isNotEmpty()) {
                        if (paramsTypes.first() == Location::class.java) {
                            // Hook and modify LocationListener#onLocationChanged(Location)
                            beforeHookedMethod(target, *paramsTypes) { hookParam ->
                                synchronized(locker) {
                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("<<<<<<<<<<<<<<<<<<")
                                    }
                                    val originalLocation = hookParam.args[0] as? Location
                                    if (originalLocation != null) {
                                        hookParam.args[0] = modifyLocationToGcj02(originalLocation, "hookLocationListener-${method.name}")
                                    }
                                }
                            }
                        } else if (List::class.java.isAssignableFrom(paramsTypes.first())) {
                            // Hook and modify LocationListener#onLocationChanged(List<Location>)
                            beforeHookedMethod(target, *paramsTypes) { hookParam ->
                                synchronized(locker) {
                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("<<<<<<<<<<<<<<<<<<")
                                    }
                                    val originalLocationList = hookParam.args[0] as? List<*>
                                    if (originalLocationList != null && originalLocationList.isNotEmpty()) {
                                        hookParam.args[0] = originalLocationList.map {
                                            if (it is Location) {
                                                modifyLocationToGcj02(it, "hookLocationListener-${method.name}")
                                            } else {
                                                it
                                            }
                                        }.toMutableList()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun hookILocationListenerOfSystemService() {
        if (!HOOK_LOCATION_LISTENER) {
            return
        }

        logcatInfo { "hookILocationListenerOfSystemService" }

        val onLocationChanged = "onLocationChanged"
        XposedHelpers.findClassIfExists("android.location.ILocationListener", LocationListener::class.java.classLoader)?.run {
            for (method in declaredMethods) {
                if (method.name == onLocationChanged) {
                    val target = method.name
                    val paramsTypes = method.parameterTypes
                    if (paramsTypes.isNotEmpty()) {
                        if (paramsTypes.first() == Location::class.java) {
                            // Hook and modify LocationListener#onLocationChanged(Location)
                            beforeHookedMethod(target, *paramsTypes) { hookParam ->
                                synchronized(locker) {
                                    toast {
                                        "invoked: ${hookParam.method}"
                                    }
                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("<<<<<<<<<<<<<<<<<<")
                                    }
                                    val originalLocation = hookParam.args[0] as? Location
                                    if (originalLocation != null) {
                                        hookParam.args[0] = modifyLocationToGcj02(originalLocation, "hookILocationListenerOfSystemService-${method.name}")
                                    }
                                }
                            }
                        } else if (List::class.java.isAssignableFrom(paramsTypes.first())) {
                            // Hook and modify LocationListener#onLocationChanged(List<Location>)
                            beforeHookedMethod(target, *paramsTypes) { hookParam ->
                                synchronized(locker) {
                                    toast {
                                        "invoked: ${hookParam.method}"
                                    }
                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("<<<<<<<<<<<<<<<<<<")
                                    }
                                    val originalLocationList = hookParam.args[0] as? List<*>
                                    if (originalLocationList != null && originalLocationList.isNotEmpty()) {
                                        hookParam.args[0] = originalLocationList.map {
                                            if (it is Location) {
                                                modifyLocationToGcj02(it, "hookILocationListenerOfSystemService-${method.name}")
                                            } else {
                                                it
                                            }
                                        }.toMutableList()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } ?: run {
            toast {
                "NotFound: ILocationListener"
            }
        }
    }

    private fun hookProviderState() {
        logcatInfo { "hookProviderState" }

        val disabledProviderList: List<String> = run {
            val providers = mutableListOf<String>()
            if (config.makeWifiLocationFail) {
                makeWifiLocationFail()
            }
            if (config.makeCellLocationFail) {
                makeCellLocationFail()
                makeTelLocationFail()
            }
            if (config.makeWifiLocationFail && config.makeCellLocationFail) {
                providers.add(LocationManager.NETWORK_PROVIDER)
            }
            if (config.makePassiveLocationFail) {
                providers.add(LocationManager.PASSIVE_PROVIDER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (config.makeFusedLocationFail) {
                    providers.add(LocationManager.FUSED_PROVIDER)
                }
            }
            return@run if (providers.isEmpty()) emptyList<String>() else providers.toList()
        }

        logcatInfo { "hookProviderState: disabled=${disabledProviderList}" }
        if (disabledProviderList.isEmpty() && !debuggable()) {
            return
        }

        val methodList = mutableListOf<String>()
        val isLocationEnabledForUser = "isLocationEnabledForUser".also { methodList.add(it) }
        val isProviderEnabledForUser = "isProviderEnabledForUser".also { methodList.add(it) }
        val hasProvider = "hasProvider".also { methodList.add(it) }
        val getProviders = "getProviders".also { methodList.add(it) }
        val getAllProviders = "getAllProviders".also { methodList.add(it) }
        val getBestProvider = "getBestProvider".also { methodList.add(it) }

        LocationManager::class.java.apply {
            for (method in declaredMethods) {
                if (method.name in methodList) {
                    val target = method.name
                    val paramsTypes = method.parameterTypes
                    afterHookedMethod(target, *paramsTypes) { hookParam ->
                        synchronized(locker) {
                            logcat {
                                info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                // info("\tfrom:")
                                // Throwable().stackTrace.forEach {
                                //     info("\t\t$it")
                                // }
                            }
                            when (hookParam.method.name) {
                                isLocationEnabledForUser -> {}
                                isProviderEnabledForUser, hasProvider -> {
                                    if (disabledProviderList.isNotEmpty()) {
                                        val provider = hookParam.args.find { it is String }
                                        if (provider in disabledProviderList) {
                                            hookParam.result = false
                                        }
                                    }
                                }
                                getProviders, getAllProviders -> {
                                    if (disabledProviderList.isNotEmpty()) {
                                        val provider = hookParam.args.find { it is String }
                                        if (provider in disabledProviderList) {
                                            val providers = hookParam.result as List<*>
                                            if (provider in providers) {
                                                @Suppress("UNCHECKED_CAST")
                                                val newProviders = providers.toMutableList() as MutableList<String>
                                                newProviders.remove(provider)
                                                hookParam.result = newProviders
                                            }
                                        }
                                    }
                                }
                                getBestProvider -> {
                                    if (disabledProviderList.isNotEmpty()) {
                                        val provider = hookParam.result as? String
                                        if (provider in disabledProviderList) {
                                            hookParam.result = null
                                        }
                                    }
                                }
                                // else -> logcatWarn { "Unknown method: ${hookParam.method}" }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun Location.isExpired(last: CoordTransform.LatLng?): Boolean {
        val timeMs = this.safeGetTime()
        if (last != null && last.hasTimes) {
            return timeMs < last.timeMs
        }
        val currMs = System.currentTimeMillis()
        return timeMs < currMs && (currMs - timeMs) > LOCATION_EXPIRED_TIME_MS
    }

    @Suppress("SameParameterValue")
    private fun modifyLocationToGcj02(
        location: Location,
        source: String = "modify",
        keepAsLastLatLng: Boolean = true,
        keepAsLatestPureLocation: Boolean = true
    ): Location {
        logcatInfo { "modifyLocationToGcj02@${location.myHashcode()}: \n\t$location" }
        synchronized(location) {
            return location.also {
                if (!it.shouldTransform()) {
                    logcatInfo { "\tshouldTransform: false, out-of-bounds" }
                    return@also
                }
                if (it.isGcj02Location()) {
                    logcatInfo { "\tisGcj02Location: true" }
                    return@also
                }
                if (!location.isTransformable()) {
                    logcatInfo { "\tisTransformable: false" }
                    return@also
                }
                it.wgs84ToGcj02(LOCATION_READ_ONLY)?.let { (wgs84LatLng, gcj02LatLng) ->
                    if (keepAsLastLatLng) {
                        updateLastGcj02LatLng(gcj02LatLng, source)
                    }
                    if (keepAsLatestPureLocation) {
                        updateLatestPureLatLng(wgs84LatLng, gcj02LatLng, source)
                    }
                }
            }
        }
    }

    private fun noteLatLngMoving(curr: CoordTransform.LatLng, source: String) {
        synchronized(locker) {
            val last = lastGcj02LatLng
            logcat {
                error("noteLatLngMoving: ${last?.toSimpleString()}>>${curr.toSimpleString()}, from=${source}")
                last?.let {
                    val distance = last.toDistance(curr)
                    val speedMps = curr.speedMpsFrom(last)
                    error("noteLatLngMoving: distance=$distance, speedMps=${speedMps}")
                    if (distance > 100) {
                        val latTips = if (curr.latitude > last.latitude) "↑" else if (curr.latitude < last.latitude) "↓" else ""
                        val lngTips = if (curr.longitude > last.longitude) "→" else if (curr.longitude < last.longitude) "←" else ""
                        val tips = if (curr.longitude > last.longitude) "$latTips$lngTips" else "$lngTips$latTips"
                        error("noteLatLngMoving: DRIFTING!! $tips")
                        if (distance > 200) {
                            if (System.currentTimeMillis() - initMs >= if (debuggable()) 30 * 1000L else 600 * 1000L) {
                                toast { "$tips ${distance.roundToInt()} ${speedMps?.roundToInt()} ${source}" }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun updateLastGcj02LatLng(curr: CoordTransform.LatLng, source: String): CoordTransform.LatLng {
        synchronized(locker) {
            noteLatLngMoving(curr, source)
            logcat {
                val last = lastGcj02LatLng
                error("updateLastGcj02LatLng: ${last?.toSimpleString()}>>${curr.toSimpleString()}, from=${source}")
            }
            lastGcj02LatLng = curr
            return lastGcj02LatLng!!
        }
    }

    private fun getLatGcj02LatLng(): CoordTransform.LatLng? {
        synchronized(locker) {
            logcatInfo { "getLatGcj02LatLng:" }
            val expiringMs = LOCATION_LAST_GCJ02_EXPIRED_MS
            if (lastGcj02LatLng?.isExpired(expiringMs, System.currentTimeMillis()) == false) {
                logcatInfo { "\tactive" }
                return lastGcj02LatLng!!

            } else {
                // Try last provider-wgs84
                getLastKnownWgs84Location(expiringMs)?.let { (provider, location) ->
                    location.wgs84ToGcj02(LOCATION_READ_ONLY)?.let { (_, gcj02LatLng) ->
                        updateLastGcj02LatLng(gcj02LatLng, "last-known-$provider")
                        logcatInfo { "\tlast-known-$provider" }
                        return gcj02LatLng
                    }
                }
                // Try last provider-gcj02
                getLastKnownGcj02Location(expiringMs)?.let { (provider, location) ->
                    location.safeGetLatLng()?.let { gcj02LatLng ->
                        updateLastGcj02LatLng(gcj02LatLng, "last-known-$provider")
                        logcatInfo { "\tlast-known-$provider" }
                        return gcj02LatLng
                    }
                }
                return null
            }
        }
    }

    private fun updateLatestPureLatLng(wgs84: CoordTransform.LatLng, gcj02: CoordTransform.LatLng, source: String) {
        synchronized(locker) {
            logcatWarn { "updateLatestPureLatLng: ${latestPureLocation?.first}>>$wgs84, from=${source}" }
            latestPureLocation = Pair(wgs84, gcj02)
        }
    }

    private fun getLatestPureLatLng(): Pair<CoordTransform.LatLng, CoordTransform.LatLng>? {
        synchronized(locker) {
            logcatInfo { "getLatestPureLatLng:" }
            val expiringMs = LOCATION_LATEST_PURES_EXPIRED_MS
            if (latestPureLocation?.first?.isExpired(expiringMs, System.currentTimeMillis()) == false) {
                logcatInfo { "\tactive" }
                return latestPureLocation!!

            } else {
                // Try last provider-wgs84
                getLastKnownWgs84Location(expiringMs)?.let { (provider, location) ->
                    location.wgs84ToGcj02(LOCATION_READ_ONLY)?.let { (wgs84LatLng, gcj02LatLng) ->
                        updateLatestPureLatLng(wgs84LatLng, gcj02LatLng, "last-known-$provider")
                        logcatInfo { "\tlast-known-$provider" }
                        return latestPureLocation!!
                    }
                }

                run pureGcj02LatLng@{
                    // Try last provider-gcj02
                    getLastKnownGcj02Location(expiringMs)?.let { (provider, location) ->
                        location.safeGetLatLng()?.let { gcj02LatLng ->
                            logcatInfo { "\tlast-known-$provider" }
                            return@pureGcj02LatLng gcj02LatLng
                        }
                    }
                    // try last updated gcj02
                    return@pureGcj02LatLng lastGcj02LatLng?.also {
                        logcatInfo { "\tlast-gcj02(cache)" }
                    }
                }?.let { pureGcj02LatLng ->
                    CoordTransform.gcj02ToWgs84(pureGcj02LatLng)?.let { wgs84LatLng ->
                        if (pureGcj02LatLng.hasTimes) {
                            wgs84LatLng.setTimes(pureGcj02LatLng.timeMs, pureGcj02LatLng.elapsedRealtimeNanos)
                        }
                        if (pureGcj02LatLng.hasSpeedAndBearing) {
                            wgs84LatLng.setSpeedAndBearing(pureGcj02LatLng.speed, pureGcj02LatLng.bearing)
                        }
                        return Pair(wgs84LatLng, pureGcj02LatLng)
                    }
                }

                return null
            }
        }
    }

    private fun getLastKnownWgs84Location(expiringMs: Long): Pair<String, Location>? {
        val currMs = System.currentTimeMillis()
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER, // network location always be pure?
        ).forEach { provider ->
            safeGetLastKnownLocationInternal(provider)?.let {
                if (it.safeGetTime() <= currMs && (currMs - it.safeGetTime()) <= expiringMs) {
                    return Pair(provider, it)
                }
            }
        }
        return  null
    }

    private fun getLastKnownGcj02Location(expiringMs: Long): Pair<String, Location>? {
        val currMs = System.currentTimeMillis()
        val getLastKnownGcj02Location: (String) -> Location? = getLastKnownGcj02@{ gcj02Provider ->
            safeGetLastKnownLocationInternal(gcj02Provider)?.let {
                if (it.safeGetTime() <= currMs && (currMs - it.safeGetTime()) <= expiringMs) {
                    return@getLastKnownGcj02 it
                }
            }
            return@getLastKnownGcj02 null
        }
        val providers = mutableListOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        providers.forEach { provider ->
            val gcj02Provider = "${provider}@gcj02"
            getLastKnownGcj02Location(gcj02Provider)?.let {
                return Pair(gcj02Provider, it)
            }
        }
        return  null
    }

    private fun safeGetLastKnownLocationInternal(provider: String): Location? {
        AndroidAppHelper.currentApplication()?.getSystemService(LocationManager::class.java)?.let {
            return it.safeGetLastKnownLocation(provider)?.also { location ->
                logcat {
                    info(">>>>>>>>>>>>>>>>>>")
                    info("safeGetLastKnownLocation: ${provider}")
                    info("\tfrom:")
                    Throwable().stackTrace.forEach {
                        info("\t\t$it")
                    }
                    info("\ttime: ${simpleDateFormat.format(location.safeGetTime())}, expired=${location.isExpired(lastGcj02LatLng)}")
                    info("\t$location")
                    info("\tprovider: ${location.safeGetProvider()}")
                    info("<<<<<<<<<<<<<<<<<<")
                }
            }
        }
        return null
    }
}
