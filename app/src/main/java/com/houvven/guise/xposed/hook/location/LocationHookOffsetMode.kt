package com.houvven.guise.xposed.hook.location

import android.app.AndroidAppHelper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.hook.*
import com.houvven.ktx_xposed.logger.*
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class LocationHookOffsetMode(override val config: ModuleConfig) : LocationHookBase(config) {
    private val initMs = System.currentTimeMillis()
    private val locker by lazy { this }
    private val simpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }

    private var lastGcj02LatLng: CoordTransform.LatLng? = null
        set(value) {
            logcatWarn { "updateLastLatLng: [${field?.latitude}, ${field?.longitude}] >> [${value?.latitude},${value?.longitude}]" }
            field = value
        }

    // Pure Location(wgs84, gcj02) for deferring fused location type
    private var latestPureLocation: Pair<CoordTransform.LatLng, CoordTransform.LatLng>? = null
        set(value) {
            logcatWarn { "latestPureLocation: [${field?.first?.latitude}, ${field?.first?.longitude}] >> [${value?.first?.latitude},${value?.first?.longitude}]" }
            field = value
        }

    private val listenerHolder: MutableMap<Int, LocationListener> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { hashMapOf() }

    override fun start() {
        // Location APIs
        hookLocation()
        hookGetLastLocation()
        hookLocationUpdateRequest()
        hookLocationListener()
        // hookILocationListenerOfSystemService()

        // Others
        if (config.makeWifiLocationFail) {
            makeWifiLocationFail()
        }
        if (config.makeCellLocationFail) {
            makeCellLocationFail()
            makeTelLocationFail()
        }

        hookProviderState()

        // GPS
        removeNmeaListener()
        // hookGnssStatus()
        // hookGpsStatus()
        // hookGpsStatusListener()
    }

    private fun hookLocation() {
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
                                    logcat {
                                        info(">>>>>>>>>>>>>>>>>>")
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${method.name}@${location.myHashcode()}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
                                        info("\ttime: ${simpleDateFormat.format(location.safeGetTime())}, expired=${location.isExpired(lastGcj02LatLng)}")
                                        info("\t$location")
                                        info("\tprovider: ${location.safeGetProvider()}")
                                    }

                                    val lastLatLng = lastGcj02LatLng
                                    val old = hookParam.result

                                    val onRely = { mode: String ->
                                        hasConsumed = true
                                        logcat {
                                            info("onRely")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: [${lastLatLng?.latitude}, ${lastLatLng?.longitude}]")
                                            info("\tlatest-pure: [${latestPureLocation?.first?.latitude}, ${latestPureLocation?.first?.longitude}]")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }
                                    val onTransForm = { mode: String, currGcj02: CoordTransform.LatLng? ->
                                        hasConsumed = true
                                        if (currGcj02 != null) {
                                            updateLastGcj02LatLng(currGcj02, "hookLocation#${method.name}-$mode")
                                        }
                                        logcat {
                                            info("onTransForm")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: [${lastLatLng?.latitude}, ${lastLatLng?.longitude}]")
                                            info("\tlatest-pure: [${latestPureLocation?.first?.latitude}, ${latestPureLocation?.first?.longitude}]")
                                            info("\t${method.name} ${if (old == hookParam.result) "==" else ">>"}: $old to ${hookParam.result}")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }
                                    val onDrop = { mode: String ->
                                        hasConsumed = true
                                        logcat {
                                            info("onDrop")
                                            info("\tmode: $mode")
                                            info("\tlast-gcj02: [${lastLatLng?.latitude}, ${lastLatLng?.longitude}]")
                                            info("\tlatest-pure: [${latestPureLocation?.first?.latitude}, ${latestPureLocation?.first?.longitude}]")
                                            info("\t${method.name} ${if (old == hookParam.result) "==" else ">>"}: $old to ${hookParam.result}")
                                            info("<<<<<<<<<<<<<<<<<<")
                                        }
                                    }

                                    synchronized(location) {
                                        if (location.isExpired(lastGcj02LatLng)) {
                                            onRely("expired")
                                            return@afterHookedMethod
                                        }
                                        if (location.isGcj02Location()) {
                                            onRely("gcj-02")
                                            return@afterHookedMethod

                                        }
                                        if (location.isTransformable(true)) {
                                            val pair = location.wgs84ToGcj02()?.also { (_, gcj02LatLng) ->
                                                when (method.name) {
                                                    "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                    "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                }
                                            }
                                            onTransForm("transform", pair?.second)
                                            return@afterHookedMethod
                                        }
                                        if (location.isReliableFused(lastGcj02LatLng)) {
                                            var isWgs84Fused = false
                                            var isGcj02Fused = false
                                            val block = checkWgs84FusedOrGcj02Fused@ {
                                                val currLatLng = location.safeGetLatLng()
                                                val latestPureLocation = getLatestPureLatLng()
                                                if (currLatLng != null && latestPureLocation != null) {
                                                    run {
                                                        val tolerance = 20 // tolerance(meter)
                                                        val distanceToWgs84 = currLatLng.toDistance(latestPureLocation.first)
                                                        val distanceToGcj02 = currLatLng.toDistance(latestPureLocation.second)
                                                        if (abs(distanceToWgs84) <= tolerance || abs(distanceToGcj02) <= tolerance) {
                                                            if (abs(distanceToWgs84) < abs(distanceToGcj02)) {
                                                                isWgs84Fused = true
                                                            } else {
                                                                isGcj02Fused = true
                                                            }
                                                            return@checkWgs84FusedOrGcj02Fused
                                                        }
                                                    }
                                                    run {
                                                        val tolerance = 60 // tolerance(meterPerSec)
                                                        val speedFromWgs84Mps = currLatLng.speedMps(latestPureLocation.first)
                                                        val speedFromGcj02Mps = currLatLng.speedMps(latestPureLocation.second)
                                                        if (abs(speedFromWgs84Mps) <= tolerance || abs(speedFromGcj02Mps) <= tolerance) {
                                                            if (abs(speedFromWgs84Mps) < abs(speedFromGcj02Mps)) {
                                                                isWgs84Fused = true
                                                            } else {
                                                                isGcj02Fused = true
                                                            }
                                                            return@checkWgs84FusedOrGcj02Fused
                                                        }
                                                    }
                                                }
                                            }

                                            block()

                                            if (isWgs84Fused) {
                                                val pair = location.wgs84ToGcj02()?.also { (_, gcj02LatLng) ->
                                                    when (method.name) {
                                                        "getLatitude" -> hookParam.result = gcj02LatLng.latitude
                                                        "getLongitude" -> hookParam.result = gcj02LatLng.longitude
                                                    }
                                                }
                                                onTransForm("fused-wsj84", pair?.second)
                                                return@afterHookedMethod

                                            }

                                            if (isGcj02Fused) {
                                                onRely("fused-gcj02")
                                                return@afterHookedMethod
                                            }

                                            // // Try pass by the last gcj-02 location
                                            // lastGcj02LatLng?.let {
                                            //     location.safeSetLatLng(it)
                                            //     when (method.name) {
                                            //         "getLatitude" -> hookParam.result = it.latitude
                                            //         "getLongitude" -> hookParam.result = it.longitude
                                            //     }
                                            // }
                                            // onDrop("fused-cache")
                                            // return@afterHookedMethod
                                        }

                                        run {
                                            val reversedLatLng = location.tryReverseTransform(lastGcj02LatLng)
                                            if (reversedLatLng != null) {
                                                location.safeSetLatLng(reversedLatLng)
                                                when (method.name) {
                                                    "getLatitude" -> hookParam.result = reversedLatLng.latitude
                                                    "getLongitude" -> hookParam.result = reversedLatLng.longitude
                                                }
                                                onTransForm("reverse", reversedLatLng)
                                                return@afterHookedMethod
                                            }
                                        }

                                        run {
                                            // Try pass by the last gcj-02 location as cache
                                            val currMs = location.safeGetTime()
                                            if (lastGcj02LatLng != null && TimeUnit.MILLISECONDS.toSeconds(abs(currMs - lastGcj02LatLng!!.timeMs)) <= 10L) { // 10s
                                                val mode = "cache"
                                                lastGcj02LatLng?.let {
                                                    location.safeSetLatLng(it)
                                                    when (method.name) {
                                                        "getLatitude" -> hookParam.result = it.latitude
                                                        "getLongitude" -> hookParam.result = it.longitude
                                                    }
                                                }
                                                onDrop(mode)
                                                return@afterHookedMethod
                                            }
                                        }

                                        // WTF states
                                        val currMs = location.safeGetTime()
                                        logcatWarn { "\ttime ago: $currMs - ${lastGcj02LatLng?.timeMs} = ${TimeUnit.MILLISECONDS.toSeconds((currMs - (lastGcj02LatLng?.timeMs ?: 0)))}s" }
                                        onRely("unknown")
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
                XposedHelpers.findAndHookConstructor(
                    this,
                    this.javaClass.classLoader,
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
                    this.javaClass.classLoader,
                    Location::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(hookParam: MethodHookParam) {
                            logcat {
                                info("onConstructorInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                            }
                        }
                    }
                )
            }
        }
    }

    private fun hookGetLastLocation() {
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
        if (!debuggable()) {
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
                                info("onMethodInvoke ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                // info("\tfrom:")
                                // Throwable().stackTrace.forEach {
                                //     info("\t\t$it")
                                // }
                            }
                            when (hookParam.method.name) {
                                isLocationEnabledForUser -> {}
                                isProviderEnabledForUser -> {}
                                hasProvider -> {}
                                getProviders -> {}
                                getAllProviders -> {}
                                getBestProvider -> {}
                                // else -> logcatWarn { "Unknown method: ${hookParam.method}" }
                            }
                        }
                    }
                }
            }

            // setMethodResult(
            //     methodName = "isLocationEnabledForUser",
            //     value = true,
            //     parameterTypes = arrayOf(UserHandle::class.java)
            // )
            // beforeHookSomeSameNameMethod(
            //     "isProviderEnabledForUser", "hasProvider"
            // ) {
            //     when (it.args[0] as String) {
            //         LocationManager.GPS_PROVIDER -> it.result = true
            //         LocationManager.FUSED_PROVIDER,
            //         LocationManager.NETWORK_PROVIDER,
            //         LocationManager.PASSIVE_PROVIDER,
            //         -> it.result = false
            //     }
            // }
            // setSomeSameNameMethodResult(
            //     "getProviders", "getAllProviders",
            //     value = listOf(LocationManager.GPS_PROVIDER)
            // )
            // setMethodResult(
            //     methodName = "getBestProvider",
            //     value = LocationManager.GPS_PROVIDER,
            //     parameterTypes = arrayOf(Criteria::class.java, Boolean::class.java)
            // )
        }
    }

    private fun Location.isExpired(last: CoordTransform.LatLng?): Boolean {
        val timeMs = this.safeGetTime()
        if (last != null && last.hasTimes) {
            return timeMs < last.timeMs
        }
        val currMs = System.currentTimeMillis()
        return timeMs < currMs && (currMs - timeMs) > 60 * 1000L
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
                if (it.isGcj02Location()) {
                    logcatInfo { "\tisGcj02Location: true" }
                    return@also
                }
                if (!location.isTransformable()) {
                    logcatInfo { "\tisTransformable: false" }
                    return@also
                }
                it.wgs84ToGcj02()?.let { (wgs84LatLng, gcj02LatLng) ->
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

    private fun updateLastGcj02LatLng(curr: CoordTransform.LatLng, source: String): CoordTransform.LatLng {
        val last = lastGcj02LatLng
        lastGcj02LatLng = curr
        last?.let {
            logcat {
                val distance = last.toDistance(curr)
                val speedMps = curr.speedMps(last)
                if (distance > 100.0f) {
                    val latTips = if (curr.latitude > last.latitude) "↑" else if (curr.latitude < last.latitude) "↓" else ""
                    val lngTips = if (curr.longitude > last.longitude) "→" else if (curr.longitude < last.longitude) "←" else ""
                    error("\tDRIFTING!! $distance, speedMps=${speedMps}, tips=$latTips$lngTips, from=$source")
                    if (System.currentTimeMillis() - initMs >= if (debuggable()) 30 * 1000L else 600 * 1000L) {
                        toast { "DRIFTING!! $distance $latTips$lngTips" }
                    }
                } else {
                    error("\tmoving: $distance, speedMps=${speedMps}, from=$source")
                }
            }
        }
        return lastGcj02LatLng!!
    }

    private fun updateLatestPureLatLng(wgs84: CoordTransform.LatLng, gcj02: CoordTransform.LatLng, source: String) {
        synchronized(locker) {
            logcatInfo { "updateLatestPureLatLng, source=${source}" }
            latestPureLocation = Pair(wgs84, gcj02)
        }
    }

    private fun getLatestPureLatLng(): Pair<CoordTransform.LatLng, CoordTransform.LatLng>? {
        synchronized(locker) {
            val expiringMs = 60 * 1000L
            if (latestPureLocation == null
                || !latestPureLocation!!.first.hasTimes
                || (System.currentTimeMillis() - latestPureLocation!!.first.timeMs) > expiringMs
            ) {
                // Try update latest pure location
                val currMs = System.currentTimeMillis()
                // try last gps
                safeGetLastKnownLocationInternal(LocationManager.GPS_PROVIDER)?.let {
                    if (it.safeGetTime() <= currMs && (currMs - it.safeGetTime()) <= expiringMs) {
                        it.wgs84ToGcj02()?.let { (wgs84LatLng, gcj02LatLng) ->
                            updateLatestPureLatLng(wgs84LatLng, gcj02LatLng, "last-gps")
                            return latestPureLocation!!
                        }
                    }
                }
                // try last network (network location always be pure?)
                safeGetLastKnownLocationInternal(LocationManager.NETWORK_PROVIDER)?.let {
                    if (it.safeGetTime() <= currMs && (currMs - it.safeGetTime()) <= expiringMs) {
                        it.wgs84ToGcj02()?.let { (wgs84LatLng, gcj02LatLng) ->
                            updateLatestPureLatLng(wgs84LatLng, gcj02LatLng, "last-gps")
                            return latestPureLocation!!
                        }
                    }
                }
                return null
            }
            return latestPureLocation!!
        }
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
