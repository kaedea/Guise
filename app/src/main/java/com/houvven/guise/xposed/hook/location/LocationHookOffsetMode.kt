package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.hook.afterHookedMethod
import com.houvven.ktx_xposed.hook.beforeHookedMethod
import com.houvven.ktx_xposed.logger.logcat
import com.houvven.ktx_xposed.logger.logcatInfo
import com.houvven.ktx_xposed.logger.logcatWarn
import de.robv.android.xposed.XposedBridge

class LocationHookOffsetMode(override val config: ModuleConfig) : LocationHookBase(config) {

    private var lastGcj02LatLng: CoordTransform.LatLng? = null
        set(value) {
            logcatWarn { "updateLastLatLng: [${field?.latitude}, ${field?.longitude}] >> [${value?.latitude},${value?.longitude}]" }
            field = value
        }

    private val listenerHolder: MutableMap<Int, LocationListener> by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { hashMapOf() }


    override fun start() {
        // Location APIs
        hookLocation()
        hookGetLastLocation()
        if (HOOK_LOCATION_LISTENER) {
            hookLocationListener()
        } else {
            hookLocationUpdateRequest()
        }

        // Others
        if (config.makeWifiLocationFail) {
            makeWifiLocationFail()
        }
        if (config.makeCellLocationFail) {
            makeCellLocationFail()
            makeTelLocationFail()
        }

        // hookProviderState()

        // GPS
        removeNmeaListener()
        // hookGnssStatus()
        // hookGpsStatus()
        // hookGpsStatusListener()
    }

    private fun hookLocation() {
        Location::class.java.run {
            declaredMethods.filter {
                (it.name == "getLatitude" || it.name == "getLongitude") && it.returnType == Double::class.java
            }.forEach { method ->
                afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                    (hookParam.thisObject as? Location)?.let { location ->
                        logcat {
                            info("onMethodInvoke ${method.name}@${location.myHashcode()}")
                            info("\tfrom:")
                            Throwable().stackTrace.forEach {
                                info("\t\t$it")
                            }
                            info("\t$location")
                            info("\tprovider: ${location.provider}")
                        }
                        synchronized(location) {
                            val mode: String
                            val lastLatLng = lastGcj02LatLng
                            val old = hookParam.result
                            if (!location.isGcj02Location()) {
                                if (location.isTransformable()) {
                                    if (location.shouldTransform()) {
                                        mode = "transform"
                                        location.wgs84ToGcj02()?.let {
                                            location.safeSetLatLng(it)
                                            updateLastLatLng(it).setTimes(location.time, location.elapsedRealtimeNanos)
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
                                        mode = "fused"
                                        location.safeGetLatLng()?.let {
                                            updateLastLatLng(it).setTimes(location.time, location.elapsedRealtimeNanos)
                                        }
                                    } else {
                                        var refineLatLng = location.tryReverseTransform(lastGcj02LatLng)
                                        if (refineLatLng != null) {
                                            mode = "reverse"
                                        } else {
                                            // Try pass by the last gcj-02 location
                                            if (lastGcj02LatLng != null &&
                                                Math.abs(location.elapsedRealtimeNanos - lastGcj02LatLng!!.elapsedRealtimeNanos) in 0..10 * 1000000L) { // 10s
                                                mode = "cache"
                                                refineLatLng = lastGcj02LatLng
                                            } else {
                                                logcatInfo {
                                                    "\ttime ago: ${location.elapsedRealtimeNanos} - ${lastGcj02LatLng?.elapsedRealtimeNanos} = " +
                                                            "${(location.elapsedRealtimeNanos - (lastGcj02LatLng?.elapsedRealtimeNanos ?: 0)) / (10 * 1000000L)} s"
                                                }
                                                mode = "unknown"
                                            }
                                        }
                                        refineLatLng?.let {
                                            location.safeSetLatLng(it)
                                            updateLastLatLng(it).setTimes(location.time, location.elapsedRealtimeNanos)
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
    }

    private fun hookGetLastLocation() {
        LocationManager::class.java.run {
            declaredMethods.filter {
                (it.name == "getLastLocation" || it.name == "getLastKnownLocation") && it.returnType == Location::class.java
            }.forEach { method ->
                afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                    (hookParam.result as? Location)?.let {
                        logcatInfo { "onMethodInvoke ${method.name}" }
                        hookParam.result = modifyLocationToGcj02(it, true)
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
                if (method.name == requestLocationUpdates || method.name == requestSingleUpdate || method.name == removeUpdates) {
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
                        afterHookedMethod(target, *paramsTypes) { hookParam ->
                            val listener = hookParam.args[indexOf] as LocationListener
                            logcat {
                                info("onMethodInvoke $target, idx=$indexOf, listener=${listener.hashCode()}")
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
                                                logcatInfo { "onLocationChanged" }
                                                listener.onLocationChanged(modifyLocationToGcj02(location, true))
                                            }
                                            override fun onLocationChanged(locations: List<Location>) {
                                                logcatInfo { "onLocationChanged list: ${locations.size}" }
                                                val fakeLocations = arrayListOf<Location>()
                                                for (location in locations) {
                                                    fakeLocations.add(modifyLocationToGcj02(location, true))
                                                }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    listener.onLocationChanged(fakeLocations)
                                                }
                                            }
                                            override fun onFlushComplete(requestCode: Int) {
                                                logcatInfo { "onFlushComplete" }
                                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                                    listener.onFlushComplete(requestCode)
                                                }
                                            }
                                            override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                                                logcatInfo { "onStatusChanged: provider=$provider, status=$status" }
                                                listener.onStatusChanged(provider, status, extras)
                                            }
                                            override fun onProviderEnabled(provider: String) {
                                                logcatInfo { "onProviderEnabled" }
                                                listener.onProviderEnabled(provider)
                                            }
                                            override fun onProviderDisabled(provider: String) {
                                                logcatInfo { "onProviderDisabled" }
                                                listener.onProviderDisabled(provider)
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
                } else {
                    // val target = method.name
                    // val paramsTypes = method.parameterTypes
                    // afterHookedMethod(target, *paramsTypes) { hookParam ->
                    //     logcatInfo { "onMethodInvoke LocationManager#$target" }
                    // }
                }
            }
        }
    }

    private fun hookLocationListener() {
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
                                logcatInfo { "onLocationChanged(Location)" }
                                val originalLocation = hookParam.args[0] as? Location
                                if (originalLocation != null) {
                                    hookParam.args[0] = modifyLocationToGcj02(originalLocation, true)
                                }
                            }
                        } else if (List::class.java.isAssignableFrom(paramsTypes.first())) {
                            // Hook and modify LocationListener#onLocationChanged(List<Location>)
                            beforeHookedMethod(target, *paramsTypes) { hookParam ->
                                val originalLocationList = hookParam.args[0] as? List<*>
                                logcatInfo { "onLocationChanged(List<Location>): ${originalLocationList?.size}" }
                                if (originalLocationList != null && originalLocationList.isNotEmpty()) {
                                    hookParam.args[0] = originalLocationList.map {
                                        if (it is Location) {
                                            modifyLocationToGcj02(it, true)
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

    @Suppress("SameParameterValue")
    private fun modifyLocationToGcj02(location: Location, keepAsLastLatLng: Boolean = true): Location {
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
                it.wgs84ToGcj02()?.let { latLng ->
                    if (keepAsLastLatLng) {
                        updateLastLatLng(latLng).setTimes(location.time, location.elapsedRealtimeNanos)
                    }
                }
            }
        }
    }

    private fun updateLastLatLng(latLng: CoordTransform.LatLng): CoordTransform.LatLng {
        val last = lastGcj02LatLng
        lastGcj02LatLng = latLng
        last?.let { start ->
            logcat {
                val distance = start.toDistance(latLng)
                if (distance > 100.0f) {
                    error("\tDRIFTING!! $distance")
                }
                error("\tmoving: $distance")
            }
        }
        return lastGcj02LatLng!!
    }
}