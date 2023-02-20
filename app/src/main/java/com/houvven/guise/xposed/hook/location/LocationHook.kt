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
import de.robv.android.xposed.XposedBridge


@Suppress("DEPRECATION")
class LocationHook : LoadPackageHandler, LocationHookBase() {

    private var latitude = config.latitude
    private var longitude = config.longitude

    init {
        if (config.randomOffset) {
            latitude += (Math.random() - 0.5) * 0.0001
            longitude += (Math.random() - 0.5) * 0.0001
        }
    }

    private val svCount = 5
    private val svidWithFlags = intArrayOf(1, 2, 3, 4, 5)
    private val cn0s = floatArrayOf(0F, 0F, 0F, 0F, 0F)
    private val elevations = cn0s.clone()
    private val azimuths = cn0s.clone()
    private val carrierFrequencies = cn0s.clone()
    private val basebandCn0DbHzs = cn0s.clone()


    override fun onHook() {

        if (longitude == -1.0 && latitude == -1.0) return

        if (config.makeWifiLocationFail) makeWifiLocationFail()
        if (config.makeCellLocationFail) makeCellLocationFail()

        fakeLatlng()
        setOtherServicesFail()  // 使其他定位服务失效
        hookGnssStatus()
        hookLocationUpdate()
        setLastLocation()
        removeNmeaListener()
        hookGpsStatus()
        hookGpsStatusListener()
    }

    private fun fakeLatlng() {
        Log.i("Xposed.location", "fixGoogleMapDrift: ${config.fixGoogleMapDrift}")
        if (config.fixGoogleMapDrift) {
            Location::class.java.run {
                declaredMethods.filter {
                    (it.name == "getLongitude" || it.name == "getLatitude") && it.returnType == Double::class.java
                }.forEach { method ->
                    Log.i("Xposed.location", "hook ${method.name}")
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        Log.i("Xposed.location", "afterHookedMethod ${method.name}")
                        val location = hookParam.thisObject as Location
                        val isFake = isFakeLocation(location)
                        Log.i("Xposed.location", "isFake $isFake")
                        if (isFake) {
                            return@afterHookedMethod
                        }
                        wgs84ToGcj02(location)?.let {
                            when(method.name) {
                                "getLongitude" -> hookParam.result = it.longitude
                                "getLatitude" -> hookParam.result = it.latitude
                            }
                        }
                    }
                }
            }
        } else {
            Location::class.java.run {
                setMethodResult("getLongitude", longitude)
                setMethodResult("getLatitude", latitude)
            }
        }
    }

    private fun setLastLocation() {
        Log.i("Xposed.location", "setLastLocation")
        if (config.fixGoogleMapDrift) {
            LocationManager::class.java.run {
                declaredMethods.filter {
                    (it.name == "getLastLocation" || it.name == "getLastKnownLocation") && it.returnType == Location::class.java
                }.forEach { method ->
                    Log.i("Xposed.location", "hook ${method.name}")
                    afterHookedMethod(method.name, *method.parameterTypes) { hookParam ->
                        Log.i("Xposed.location", "afterHookedMethod ${method.name}")
                        val location = hookParam.result as Location
                        hookParam.result = modifyLocation(location)
                    }
                }
            }
        } else {
            LocationManager::class.java.setSomeSameNameMethodResult(
                "getLastLocation",
                "getLastKnownLocation",
                value = modifyLocation(Location(LocationManager.GPS_PROVIDER))
            )
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

        if (config.fixGoogleMapDrift) {
            LocationManager::class.java.run {
                var target: String
                for (method in declaredMethods) {
                    if (method.name != requestLocationUpdates && method.name != requestSingleUpdate) continue
                    val indexOf = method.parameterTypes.indexOf(LocationListener::class.java)
                    if (indexOf == -1) continue
                    val paramsTypes = method.parameterTypes
                    target = method.name
                    Log.i("Xposed.location", "hook: $target")
                    beforeHookedMethod(target, *paramsTypes) {
                        Log.i("Xposed.location", "beforeHookedMethod $target, idx=$indexOf")
                        val listener = it.args[indexOf] as LocationListener
                        val wrapper: LocationListener = object : LocationListener {
                            override fun onLocationChanged(location: Location) {
                                Log.i("Xposed.location", "onLocationChanged")
                                listener.onLocationChanged(modifyLocation(location))
                            }

                            override fun onLocationChanged(locations: List<Location>) {
                                Log.i("Xposed.location", "onLocationChanged list: ${locations.size}")
                                val fakeLocations = arrayListOf<Location>()
                                for (location in locations) {
                                    fakeLocations.add(modifyLocation(location))
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

                            override fun onStatusChanged(
                                provider: String,
                                status: Int,
                                extras: Bundle
                            ) {
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
                        XposedBridge.invokeOriginalMethod(method, it.thisObject, it.args)
                    }
                }
            }
        } else {
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
                        val location = modifyLocation(Location(LocationManager.GPS_PROVIDER))
                        listener.onLocationChanged(location)
                    }
                }
            }
        }
    }

    private fun removeNmeaListener() {
        LocationManager::class.java.setAllMethodResult("addNmeaListener", false)
    }

    private fun modifyLocation(location: Location): Location {
        return location.also {
            if (config.fixGoogleMapDrift) {
                if (isFakeLocation(it)) {
                    Log.i("Xposed.location", "isFakeLocation true")
                    return@also
                }
                Log.i("Xposed.location", "get real location: $it")
                wgs84ToGcj02(it)?.let { newLocation ->
                    it.latitude = newLocation.latitude
                    it.longitude = newLocation.longitude
                    it.time = System.currentTimeMillis()
                    it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                    Log.i("Xposed.location", "set fake location: $it")
                }
            } else {
                it.longitude = longitude
                it.latitude = latitude
                it.provider = LocationManager.GPS_PROVIDER
                it.accuracy = 10.0f
                it.time = System.currentTimeMillis()
                it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
        }
    }

    private fun wgs84ToGcj02(location: Location): CoordTransform.LatLng? {
        if (!config.fixGoogleMapDrift || isFakeLocation(location)) {
            throw UnsupportedOperationException("Not supported: fixGoogleMapDrift=${config.fixGoogleMapDrift}, isFakeLocation=${isFakeLocation(location)}")
        }
        Log.i("Xposed.location", "wgs84ToGcj02")
        location.extras = location.let bundle@{
            val bundle = if (it.extras != null) it.extras else Bundle()
            bundle!!.putBoolean("wgs2gcj", true)
            return@bundle bundle
        }
        return CoordTransform.wgs84ToGcj02(CoordTransform.LatLng(location.latitude, location.longitude))
    }

    private fun isFakeLocation(it: Location) = it.extras != null && it.extras!!.getBoolean("wgs2gcj", false)
}