package com.houvven.guise.xposed.hook.location

import android.location.*
import android.location.GpsStatus.GPS_EVENT_FIRST_FIX
import android.location.GpsStatus.GPS_EVENT_STARTED
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.houvven.guise.xposed.LoadPackageHandler
import com.houvven.ktx_xposed.hook.*


@Suppress("DEPRECATION")
class LocationHook : LoadPackageHandler, LocationHookBase() {

    private var fakeLatitude = config.latitude
    private var fakeLongitude = config.longitude
    private var lastGcj02LatLng: CoordTransform.LatLng? = null

    private val svCount = 5
    private val svidWithFlags = intArrayOf(1, 2, 3, 4, 5)
    private val cn0s = floatArrayOf(0F, 0F, 0F, 0F, 0F)
    private val elevations = cn0s.clone()
    private val azimuths = cn0s.clone()
    private val carrierFrequencies = cn0s.clone()
    private val basebandCn0DbHzs = cn0s.clone()

    private val isFakeLocationMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { config.latitude != -1.0 && config.longitude != -1.0 && !isFixGoogleMapDriftMode }
    private val isFixGoogleMapDriftMode by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { config.fixGoogleMapDrift }

    override fun onHook() {
        Log.i("Xposed.location", "onHook config: latitude=${config.latitude}, longitude=${config.longitude}, fixGoogleMapDrift=${config.fixGoogleMapDrift}")
        if (!isFakeLocationMode && !isFixGoogleMapDriftMode) {
            // LocationHook Disabled
            return
        }

        // LocationHook Enabled
        if (config.randomOffset) {
            fakeLatitude += (Math.random() - 0.5) * 0.0001
            fakeLongitude += (Math.random() - 0.5) * 0.0001
        }
        if (config.makeWifiLocationFail) makeWifiLocationFail()
        if (config.makeCellLocationFail) makeCellLocationFail()

        fakeLatlng()
        setLastLocation()
        hookLocationUpdate()

        if (isFakeLocationMode) {
            setOtherServicesFail()  // 使其他定位服务失效
            hookGnssStatus()
            removeNmeaListener()
            hookGpsStatus()
            hookGpsStatusListener()
        }
        if (isFixGoogleMapDriftMode) {
        }
    }

    private fun fakeLatlng() {
        Log.i("Xposed.location", "fakeLatlng")
        if (isFakeLocationMode) {
            Location::class.java.run {
                setMethodResult("getLongitude", fakeLongitude)
                setMethodResult("getLatitude", fakeLatitude)
            }

        } else if (isFixGoogleMapDriftMode) {
            Location::class.java.run {
                declaredMethods.filter {
                    (it.name == "getLatitude" || it.name == "getLongitude") && it.returnType == Double::class.java
                }.forEach { method ->
                    Log.i("Xposed.location", "hook ${method.name}")
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        (hookParam.thisObject as? Location)?.let { location ->
                            Log.i("Xposed.location", "afterHookedMethod $location")
                            Log.i("Xposed.location", "isGcj02=${location.isGcj02Location()}, isTransformable=${location.isTransformable()}")
                            val old = hookParam.result
                            if (!location.isGcj02Location()) {
                                if (location.isTransformable()) {
                                    location.wgs84ToGcj02()?.let {
                                        when (method.name) {
                                            "getLatitude" -> hookParam.result = it.latitude
                                            "getLongitude" -> hookParam.result = it.longitude
                                        }
                                    }
                                } else {
                                    Log.i("Xposed.location", "extras ${location.extras}")
                                    // Get the last gcj-02 location
                                    lastGcj02LatLng?.let {
                                        when (method.name) {
                                            "getLatitude" -> hookParam.result = it.latitude
                                            "getLongitude" -> hookParam.result = it.longitude
                                        }
                                    }
                                }
                            }
                            Log.i("Xposed.location", "${method.name}: $old ${if (old == hookParam.result) "==" else ">>"} ${hookParam.result}")
                        }
                    }
                }
            }
        }
    }

    private fun setLastLocation() {
        Log.i("Xposed.location", "setLastLocation")
        if (isFakeLocationMode) {
            LocationManager::class.java.setSomeSameNameMethodResult(
                "getLastLocation",
                "getLastKnownLocation",
                value = modifyLocationToFake(Location(LocationManager.GPS_PROVIDER))
            )

        } else if (isFixGoogleMapDriftMode) {
            LocationManager::class.java.run {
                declaredMethods.filter {
                    (it.name == "getLastLocation" || it.name == "getLastKnownLocation") && it.returnType == Location::class.java
                }.forEach { method ->
                    Log.i("Xposed.location", "hook ${method.name}")
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        (hookParam.result as? Location)?.let {
                            Log.i("Xposed.location", "afterHookedMethod ${method.name}")
                            hookParam.result = modifyLocationToGcj02(it)
                        }
                    }
                }
            }
        }
    }

    private fun hookGpsStatusListener() {
        LocationManager::class.java.afterHookedMethod(
            methodName = "addGpsStatusListener",
            GpsStatus.Listener::class.java
        ) { param ->
            (param.args[0] as GpsStatus.Listener?)?.run {
                callMethod("onGpsStatusChanged", GPS_EVENT_STARTED)
                callMethod("onGpsStatusChanged", GPS_EVENT_FIRST_FIX)
            }
        }
    }

    private fun hookGpsStatus() {
        LocationManager::class.java.beforeHookedMethod(
            methodName = "getGpsStatus",
            GpsStatus::class.java
        ) { param ->
            val status = param.args[0] as GpsStatus? ?: return@beforeHookedMethod

            val method = GpsStatus::class.java.findMethodExactIfExists(
                "setStatus",
                Int::class.java,
                Array::class.java,
                Array::class.java,
                Array::class.java,
                Array::class.java
            )

            val method2 = GpsStatus::class.java.findMethodExactIfExists(
                "setStatus",
                GnssStatus::class.java,
                Int::class.java
            )

            if (method == null && method2 == null) {
                return@beforeHookedMethod
            }

            {
                method?.invoke(status, svCount, svidWithFlags, cn0s, elevations, azimuths)
                GnssStatus::class.java.callStaticMethodIfExists(
                    "wrap",
                    svCount,
                    svidWithFlags,
                    cn0s,
                    elevations,
                    azimuths,
                    carrierFrequencies,
                    basebandCn0DbHzs
                )?.let {
                    it as GnssStatus
                    method2?.invoke(status, it, System.currentTimeMillis().toInt())
                }
            }.let {
                it()
                param.args[0] = status
                param.result = status
                it()
                param.result = status
            }
        }
    }

    private fun hookGnssStatus() {
        GnssStatus::class.java.beforeHookConstructor(
            Int::class.java,
            IntArray::class.java,
            FloatArray::class.java,
            FloatArray::class.java,
            FloatArray::class.java,
            FloatArray::class.java,
            FloatArray::class.java
        ) {
            it.args[0] = svCount
            it.args[1] = svidWithFlags
            it.args[2] = cn0s
            it.args[3] = elevations
            it.args[4] = azimuths
            it.args[5] = carrierFrequencies
            it.args[6] = basebandCn0DbHzs
        }
    }

    private fun hookLocationUpdate() {
        Log.i("Xposed.location", "hookLocationUpdate")
        val requestLocationUpdates = "requestLocationUpdates"
        val requestSingleUpdate = "requestSingleUpdate"
        val getCurrentLocation = "getCurrentLocation"

        if (isFakeLocationMode) {
            LocationManager::class.java.run {
                var target: String
                for (method in declaredMethods) {
                    if (method.name != requestLocationUpdates && method.name != requestSingleUpdate) continue
                    val indexOf = method.parameterTypes.indexOf(LocationListener::class.java)
                    if (indexOf == -1) continue
                    val paramsTypes = method.parameterTypes
                    target = method.name
                    afterHookedMethod(target, *paramsTypes) {
                        val listener = it.args[indexOf] as LocationListener
                        val location = modifyLocationToFake(Location(LocationManager.GPS_PROVIDER))
                        listener.onLocationChanged(location)
                    }
                }
            }

        } else if (isFixGoogleMapDriftMode) {
            LocationManager::class.java.run {
                for (method in declaredMethods) {
                    if (method.name != requestLocationUpdates && method.name != requestSingleUpdate && method.name != getCurrentLocation) {
                        continue
                    }
                    val target = method.name
                    val paramsTypes = method.parameterTypes
                    val indexOf = method.parameterTypes.indexOf(LocationListener::class.java)
                    if (indexOf == -1) {
                        // Just intercept invocation right now
                        // Log.i("Xposed.location", "replace: $target($paramsTypes)")
                        // replaceMethod(this, target, *paramsTypes) {
                        //     Log.i("Xposed.location", "replaceMethod $target $paramsTypes")
                        // }
                        continue
                    } else {
                        // Hook and modify location
                        Log.i("Xposed.location", "hook: $target")
                        beforeHookedMethod(target, *paramsTypes) {
                            Log.i("Xposed.location", "beforeHookedMethod $target, idx=$indexOf")
                            val listener = it.args[indexOf] as LocationListener
                            val wrapper: LocationListener = object : LocationListener {
                                override fun onLocationChanged(location: Location) {
                                    Log.i("Xposed.location", "onLocationChanged")
                                    listener.onLocationChanged(modifyLocationToGcj02(location))
                                }

                                override fun onLocationChanged(locations: List<Location>) {
                                    Log.i("Xposed.location", "onLocationChanged list: ${locations.size}")
                                    val fakeLocations = arrayListOf<Location>()
                                    for (location in locations) {
                                        fakeLocations.add(modifyLocationToGcj02(location))
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        listener.onLocationChanged(fakeLocations)
                                    }
                                }

                                override fun onFlushComplete(requestCode: Int) {
                                    Log.i("Xposed.location", "onFlushComplete")
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        listener.onFlushComplete(requestCode)
                                    }
                                }

                                override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
                                    Log.i("Xposed.location", "onStatusChanged provider")
                                    listener.onStatusChanged(provider, status, extras)
                                }

                                override fun onProviderEnabled(provider: String) {
                                    Log.i("Xposed.location", "onProviderEnabled")
                                    listener.onProviderEnabled(provider)
                                }

                                override fun onProviderDisabled(provider: String) {
                                    Log.i("Xposed.location", "onProviderDisabled")
                                    listener.onProviderDisabled(provider)
                                }
                            }
                            it.args[indexOf] = wrapper
                        }
                    }
                }
            }
        }
    }

    private fun removeNmeaListener() {
        LocationManager::class.java.setAllMethodResult("addNmeaListener", false)
    }

    private fun modifyLocationToFake(location: Location): Location {
        Log.i("Xposed.location", "modifyLocationToFake: $location")
        if (!isFakeLocationMode) {
            throw IllegalStateException("isFakeLocationMode=${isFakeLocationMode}")
        }
        return location.also {
            it.longitude = fakeLongitude
            it.latitude = fakeLatitude
            it.provider = LocationManager.GPS_PROVIDER
            it.accuracy = 10.0f
            it.time = System.currentTimeMillis()
            it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            it.extras = it.let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("isFake", true)
                }
                return@bundle bundle
            }
        }
    }

    private fun modifyLocationToGcj02(location: Location): Location {
        Log.i("Xposed.location", "modifyLocationToGcj02: $location")
        if (!isFixGoogleMapDriftMode) {
            throw IllegalStateException("isFixGoogleMapDriftMode=${isFixGoogleMapDriftMode}")
        }
        return location.also {
            if (it.isGcj02Location()) {
                return@also
            }
            if (!location.isTransformable()) {
                Log.i("Xposed.location", "isTransformable: ${location.isTransformable()}")
                return@also
            }
            it.wgs84ToGcj02()?.let { latLng ->
                lastGcj02LatLng = latLng
                // it.time = System.currentTimeMillis()
                // it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        }
    }
}

