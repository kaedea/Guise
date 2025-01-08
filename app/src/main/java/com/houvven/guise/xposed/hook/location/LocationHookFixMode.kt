package com.houvven.guise.xposed.hook.location

import android.location.*
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.hook.*
import com.houvven.ktx_xposed.logger.logcatInfo

class LocationHookFixMode(override val config: ModuleConfig) : LocationHookBase(config) {
    private var fakeLatitude = config.latitude
    private var fakeLongitude = config.longitude

    private val svCount = 5
    private val svidWithFlags = intArrayOf(1, 2, 3, 4, 5)
    private val cn0s = floatArrayOf(0F, 0F, 0F, 0F, 0F)
    private val elevations = cn0s.clone()
    private val azimuths = cn0s.clone()
    private val carrierFrequencies = cn0s.clone()
    private val basebandCn0DbHzs = cn0s.clone()

    override fun start() {
        if (config.randomOffset) {
            fakeLatitude += (Math.random() - 0.5) * 0.0001
            fakeLongitude += (Math.random() - 0.5) * 0.0001
        }

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

        hookProviderState()

        // GPS
        removeNmeaListener()
        hookGnssStatus()
        hookGpsStatus()
        hookGpsStatusListener()
    }

    private fun hookLocation() {
        Location::class.java.run {
            setMethodResult("getLongitude", fakeLongitude)
            setMethodResult("getLatitude", fakeLatitude)
        }
    }

    private fun hookGetLastLocation() {
        LocationManager::class.java.setSomeSameNameMethodResult(
            "getLastLocation",
            "getLastKnownLocation",
            value = modifyLocationToFake(Location(LocationManager.GPS_PROVIDER))
        )
    }

    private fun hookLocationUpdateRequest() {
        val requestLocationUpdates = "requestLocationUpdates"
        val requestSingleUpdate = "requestSingleUpdate"
        val removeUpdates = "removeUpdates"

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
                                    hookParam.args[0] = modifyLocationToFake(originalLocation)
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
                                            modifyLocationToFake(it)
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

    private fun modifyLocationToFake(location: Location): Location {
        logcatInfo { "modifyLocationToFake: $location" }
        return location.also {
            it.safeSetLatLng(CoordTransform.LatLng(fakeLatitude, fakeLongitude))
            it.provider = LocationManager.GPS_PROVIDER
            it.accuracy = 10.0f
            it.time = System.currentTimeMillis()
            it.elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            it.safeSetExtras(it.let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("isFake", true)
                }
                return@bundle bundle
            })
        }
    }

    private fun hookProviderState() {
        LocationManager::class.java.apply {
            setMethodResult(
                methodName = "isLocationEnabledForUser",
                value = true,
                parameterTypes = arrayOf(UserHandle::class.java)
            )
            beforeHookSomeSameNameMethod(
                "isProviderEnabledForUser", "hasProvider"
            ) {
                when (it.args[0] as String) {
                    LocationManager.GPS_PROVIDER -> it.result = true
                    LocationManager.FUSED_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                    -> it.result = false
                }
            }
            setSomeSameNameMethodResult(
                "getProviders", "getAllProviders",
                value = listOf(LocationManager.GPS_PROVIDER)
            )
            setMethodResult(
                methodName = "getBestProvider",
                value = LocationManager.GPS_PROVIDER,
                parameterTypes = arrayOf(Criteria::class.java, Boolean::class.java)
            )
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

    private fun hookGpsStatusListener() {
        LocationManager::class.java.afterHookedMethod(
            methodName = "addGpsStatusListener",
            GpsStatus.Listener::class.java
        ) { param ->
            (param.args[0] as GpsStatus.Listener?)?.run {
                callMethod("onGpsStatusChanged", GpsStatus.GPS_EVENT_STARTED)
                callMethod("onGpsStatusChanged", GpsStatus.GPS_EVENT_FIRST_FIX)
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
}
