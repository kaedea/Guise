package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.hook.*
import com.houvven.ktx_xposed.logger.*
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class LocationHookOffsetMode(override val config: ModuleConfig) : LocationHookBase(config) {
    private val initMs = System.currentTimeMillis()
    private val locker = this

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
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        synchronized(locker) {
                            (hookParam.thisObject as? Location)?.let { location ->
                                logcat {
                                    info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${method.name}@${location.myHashcode()}")
                                    info("\tfrom:")
                                    Throwable().stackTrace.forEach {
                                        info("\t\t$it")
                                    }
                                    info("\t$location")
                                    info("\tprovider: ${location.safeGetProvider()}")
                                }
                                synchronized(location) {
                                    val mode: String
                                    val lastLatLng = lastGcj02LatLng
                                    val old = hookParam.result
                                    if (!location.isGcj02Location()) {
                                        if (location.isTransformable() && location.safeGetProvider() != LocationManager.NETWORK_PROVIDER) {
                                            if (location.shouldTransform()) {
                                                mode = "transform"
                                                location.wgs84ToGcj02()?.let {
                                                    location.safeSetLatLng(it)
                                                    it.setTimes(location.safeGetTime(), location.safeGetElapsedRealtimeNanos())
                                                    updateLastLatLng(it, "hookLocation#${method.name}-$mode")
                                                    when (method.name) {
                                                        "getLatitude" -> hookParam.result = it.latitude
                                                        "getLongitude" -> hookParam.result = it.longitude
                                                    }
                                                }
                                            } else {
                                                mode = "out-of-bounds"
                                            }
                                        } else {
                                            if (location.isReliableFused(lastGcj02LatLng)) {
                                                var isWgs84Fused = false
                                                if (latestPureLocation != null) {
                                                    val currLatLng = location.safeGetLatLng()
                                                    if (currLatLng != null) {
                                                        val distanceToWgs84 = currLatLng.toDistance(latestPureLocation!!.first)
                                                        val distanceToGcj02 = currLatLng.toDistance(latestPureLocation!!.second)
                                                        if (abs(distanceToWgs84) < abs(distanceToGcj02)) {
                                                            if (abs(distanceToWgs84) <= 20) { // tolerance
                                                                isWgs84Fused = true
                                                            }
                                                        }
                                                    }
                                                }
                                                if (isWgs84Fused) {
                                                    mode = "fused-wsj84"
                                                    location.wgs84ToGcj02()?.let {
                                                        location.safeSetLatLng(it)
                                                        it.setTimes(location.safeGetTime(), location.safeGetElapsedRealtimeNanos())
                                                        updateLastLatLng(it, "hookLocation#${method.name}-$mode")
                                                        when (method.name) {
                                                            "getLatitude" -> hookParam.result = it.latitude
                                                            "getLongitude" -> hookParam.result = it.longitude
                                                        }
                                                    }
                                                } else {
                                                    var isGcj02Fused = false
                                                    if (lastGcj02LatLng != null) {
                                                        val currLatLng = location.safeGetLatLng()
                                                        if (currLatLng != null) {
                                                            val delta = currLatLng.toDistance(lastGcj02LatLng!!)
                                                            if (abs(delta) <= 20) {
                                                                isGcj02Fused = true
                                                            }
                                                        }
                                                    }
                                                    if (isGcj02Fused) {
                                                        mode = "fused-gcj02"
                                                    } else {
                                                        mode = "fused-cache"
                                                        lastGcj02LatLng?.let {
                                                            location.safeSetLatLng(it)
                                                            when (method.name) {
                                                                "getLatitude" -> hookParam.result = it.latitude
                                                                "getLongitude" -> hookParam.result = it.longitude
                                                            }
                                                        }
                                                    }
                                                    location.safeGetLatLng()?.let {
                                                        it.setTimes(location.safeGetTime(), location.safeGetElapsedRealtimeNanos())
                                                        updateLastLatLng(it, "hookLocation#${method.name}-$mode")
                                                    }
                                                }

                                            } else {
                                                var refineLatLng = location.tryReverseTransform(lastGcj02LatLng)
                                                if (refineLatLng != null) {
                                                    mode = "reverse"
                                                } else {
                                                    // Try pass by the last gcj-02 location
                                                    val currNanos = location.safeGetElapsedRealtimeNanos()
                                                    if (lastGcj02LatLng != null &&
                                                        TimeUnit.NANOSECONDS.toSeconds(abs(currNanos - lastGcj02LatLng!!.elapsedRealtimeNanos)) in 0..10L  // 10s
                                                    ) {
                                                        mode = "cache"
                                                        refineLatLng = lastGcj02LatLng
                                                    } else {
                                                        logcatInfo {
                                                            "\ttime ago: $currNanos - ${lastGcj02LatLng?.elapsedRealtimeNanos} = " +
                                                                    "${TimeUnit.NANOSECONDS.toSeconds((currNanos - (lastGcj02LatLng?.elapsedRealtimeNanos ?: 0)))} s"
                                                        }
                                                        mode = "unknown"
                                                    }
                                                }
                                                refineLatLng?.let {
                                                    location.safeSetLatLng(it)
                                                    it.setTimes(location.safeGetTime(), location.safeGetElapsedRealtimeNanos())
                                                    updateLastLatLng(it, "hookLocation#${method.name}-$mode")
                                                    when (method.name) {
                                                        "getLatitude" -> hookParam.result = it.latitude
                                                        "getLongitude" -> hookParam.result = it.longitude
                                                    }
                                                }
                                            }
                                        }
                                    } else {
                                        mode = "gcj-02"
                                    }
                                    logcat {
                                        info("\tmode: $mode")
                                        info("\tlast: [${lastLatLng?.latitude}, ${lastLatLng?.longitude}]")
                                        info("\t${method.name} ${if (old == hookParam.result) "==" else ">>"}: $old to ${hookParam.result}")
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
    }

    private fun hookGetLastLocation() {
        LocationManager::class.java.run {
            declaredMethods.filter {
                (it.name == "getLastLocation" || it.name == "getLastKnownLocation") && it.returnType == Location::class.java
            }.forEach { method ->
                afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                    synchronized(locker) {
                        (hookParam.result as? Location)?.let {
                            logcatInfo { "onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${method.name}" }
                            hookParam.result = modifyLocationToGcj02(
                                it,
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
                                    info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#$target, idx=$indexOf, listener=${listener.hashCode()}")
                                    info("\tfrom:")
                                    Throwable().stackTrace.forEach {
                                        info("\t\t$it")
                                    }
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
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
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
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
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
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
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
                                        info("onMethodInvokeHook ${hookParam.thisObject.javaClass.simpleName}#${hookParam.method.name}, args=${Arrays.toString(hookParam.args)}, result=${hookParam.result}")
                                        info("\tfrom:")
                                        Throwable().stackTrace.forEach {
                                            info("\t\t$it")
                                        }
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
                if (!location.shouldTransform()) {
                    logcatInfo { "\tshouldTransform: false" }
                    return@also
                }
                it.wgs84ToGcj02()?.let { gcj02LatLng ->
                    if (keepAsLastLatLng) {
                        gcj02LatLng.setTimes(location.safeGetTime(), location.safeGetElapsedRealtimeNanos())
                        updateLastLatLng(gcj02LatLng, source)
                    }
                    if (keepAsLatestPureLocation) {
                        it.safeGetLatLng()?.let { wgs84LatLng ->
                            latestPureLocation = Pair(wgs84LatLng, gcj02LatLng)
                        }
                    }
                }
            }
        }
    }

    private fun updateLastLatLng(latLng: CoordTransform.LatLng, source: String): CoordTransform.LatLng {
        val last = lastGcj02LatLng
        lastGcj02LatLng = latLng
        last?.let { start ->
            logcat {
                val distance = start.toDistance(latLng)
                val speedMps = latLng.speedMps(start)
                if (distance > 100.0f) {
                    error("\tDRIFTING!! $distance, speedMps=${speedMps}, from=$source")
                    if (System.currentTimeMillis() - initMs >= if (debuggable()) 30 * 1000L else 600 * 1000L) {
                        toast { "DRIFTING!! $distance" }
                    }
                } else {
                    error("\tmoving: $distance, speedMps=${speedMps}, from=$source")
                }
            }
        }
        return lastGcj02LatLng!!
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
}