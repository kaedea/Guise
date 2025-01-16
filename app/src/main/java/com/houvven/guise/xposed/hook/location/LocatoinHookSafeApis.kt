package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import com.houvven.ktx_xposed.logger.logcat
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * @author Kaede
 * @since  2025-01-13
 */

private val mDateFormat by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }


private val getLatitudeMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getLatitude").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getLongitudeMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getLongitude").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getProviderMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getProvider").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getExtrasMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getExtras").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getTimeMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getTime").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getElapsedRealtimeNanosMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getElapsedRealtimeNanos").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val hasSpeedMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "hasSpeed").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getSpeedMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getSpeed").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val hasBearingMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "hasBearing").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val getBearingMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "getBearing").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}


private val setLatitudeMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "setLatitude", Double::class.java).also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val setLongitudeMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "setLongitude", Double::class.java).also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val setProviderMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "setProvider", String::class.java).also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val setExtrasMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(Location::class.java, "setExtras", Bundle::class.java).also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val latitudeFiled by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findFieldIfExists(Location::class.java, "mLatitudeDegrees").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val longitudeField by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findFieldIfExists(Location::class.java, "mLongitudeDegrees").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val extrasField by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findFieldIfExists(Location::class.java, "mExtras").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

/**
 * Avoid recursive hooking, because we might has hooked every apis of Location
 */
internal fun Location.safeGetLatLng(): CoordTransform.LatLng? {
    val toLatLng = toLatLng@ {
        run {
            if (getLatitudeMethod != null && getLongitudeMethod != null) {
                val lat = XposedBridge.invokeOriginalMethod(
                    getLatitudeMethod,
                    this,
                    arrayOf()
                ) as? Double
                val lng = XposedBridge.invokeOriginalMethod(
                    getLongitudeMethod,
                    this,
                    arrayOf()
                ) as? Double
                if (lat != null && lng != null) {
                    return@toLatLng CoordTransform.LatLng(lat, lng)
                }
            }
        }
        run {
            val lat = latitudeFiled?.get(this) as? Double
            val lng = longitudeField?.get(this) as? Double
            if (lat != null && lng != null) {
                return@toLatLng CoordTransform.LatLng(lat, lng)
            }
        }
        run {
            val raw = toString()
            val symBgn = "Location[${safeGetProvider()} "
            val symEnd = " "
            if (raw.startsWith(symBgn)) {
                val prefix = raw.substring(raw.indexOf(symBgn) + symBgn.length)
                if (prefix.contains(symEnd)) {
                    val target = prefix.substring(0, prefix.indexOf(symEnd))
                    val split = target.split(",")
                    if (split.size == 2) {
                        return@toLatLng CoordTransform.LatLng(split[0].toDouble(), split[1].toDouble())
                    }
                }
            }
        }
        return@toLatLng null
    }
    return toLatLng()?.also {
        it.setTimes(safeGetTime(), safeGetElapsedRealtimeNanos())
        if (safeHasSpeed() && safeHasBearing()) {
            it.setSpeedAndBearing(safeGetSpeed(), safeGetBearing())
        }
    }
}

internal fun Location.safeGetProvider(): String? {
    return XposedBridge.invokeOriginalMethod(
        getProviderMethod,
        this,
        null
    ) as? String
}

internal fun Location.safeGetExtras(): Bundle? {
    return XposedBridge.invokeOriginalMethod(
        getExtrasMethod,
        this,
        null
    ) as? Bundle
}

internal fun Location.safeGetTime(): Long {
    return XposedBridge.invokeOriginalMethod(
        getTimeMethod,
        this,
        null
    ) as? Long ?: 0L
}

internal fun Location.safeGetElapsedRealtimeNanos(): Long {
    return XposedBridge.invokeOriginalMethod(
        getElapsedRealtimeNanosMethod,
        this,
        null
    ) as? Long ?: 0L
}

internal fun Location.formatTimes(): String {
    var time = "null"
    var timeAge = "null"
    safeGetTime().takeIf { it > 0 }?.let {
        time = mDateFormat.format(it)
        timeAge = "${System.currentTimeMillis() - it}ms"
    }
    var elapsedTime = "null"
    var elapsedTimeAge = "null"
    safeGetElapsedRealtimeNanos().takeIf { it > 0 }?.let {
        elapsedTime = formatMillis(TimeUnit.NANOSECONDS.toMillis(it))
        elapsedTimeAge = "${TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - it)}ms"
    }
    return "time=($time/$timeAge), elapsedTime=($elapsedTime,$elapsedTimeAge)"
}

internal fun CoordTransform.LatLng.formatTimes(): String {
    var time = "null"
    var timeAge = "null"
    timeMs.takeIf { it > 0 }?.let {
        time = mDateFormat.format(it)
        timeAge = "${System.currentTimeMillis() - it}ms"
    }
    var elapsedTime = "null"
    var elapsedTimeAge = "null"
    elapsedRealtimeNanos.takeIf { it > 0 }?.let {
        elapsedTime = formatMillis(TimeUnit.NANOSECONDS.toMillis(it))
        elapsedTimeAge = "${TimeUnit.NANOSECONDS.toMillis(SystemClock.elapsedRealtimeNanos() - it)}ms"
    }
    return "time=($time/$timeAge), elapsedTime=($elapsedTime,$elapsedTimeAge)"
}

private fun formatMillis(millis: Long): String {
    if (millis < 0) {
        return "-ms"
    }

    val ms = millis % 1000
    var remainingSeconds = millis / 1000
    val seconds = remainingSeconds % 60
    remainingSeconds /= 60
    val minutes = remainingSeconds % 60
    remainingSeconds /= 60
    val hours = remainingSeconds

    return buildString {
        if (hours > 0) append("${hours}h")
        if (minutes > 0) append("${minutes}m")
        if (seconds > 0) append("${seconds}s")
        append("${ms}ms")
    }
}

internal fun Location.safeHasSpeed(): Boolean {
    return XposedBridge.invokeOriginalMethod(
        hasSpeedMethod,
        this,
        null
    ) as? Boolean ?: false
}

internal fun Location.safeGetSpeed(): Float {
    return XposedBridge.invokeOriginalMethod(
        getSpeedMethod,
        this,
        null
    ) as? Float ?: 0F
}

internal fun Location.safeHasBearing(): Boolean {
    return XposedBridge.invokeOriginalMethod(
        hasBearingMethod,
        this,
        null
    ) as? Boolean ?: false
}

internal fun Location.safeGetBearing(): Float {
    return XposedBridge.invokeOriginalMethod(
        getBearingMethod,
        this,
        null
    ) as? Float ?: 0F
}


internal fun Location.safeSetLatLng(latLng: CoordTransform.LatLng) {
    val old = safeGetLatLng()
    if (javaClass == Location::class.java) {
        safeSetLatitude(latLng.latitude)
        safeSetLongitude(latLng.longitude)
    } else {
        latitudeFiled?.set(this, latLng.latitude)
        longitudeField?.set(this, latLng.longitude)
    }
    val new = safeGetLatLng()
    logcat {
        info("safeSetLatLng")
        info("\t[${old?.latitude}, ${old?.longitude}] >> [${latLng.latitude}, ${latLng.longitude}] = [${new?.latitude}, ${new?.longitude}]")
        info("\tchange: ${new?.equals(old)}, setSuc: ${new?.equals(latLng)}")
    }
}

internal fun Location.safeSetExtras(extras: Bundle) {
    if (javaClass == Location::class.java) {
        val myExtras = safeGetExtras()
        if (myExtras == null) {
            XposedBridge.invokeOriginalMethod(
                setExtrasMethod,
                this,
                arrayOf(extras)
            )
        } else {
            myExtras.putAll(extras)
        }
    } else {
        if (XposedHelpers.findFieldIfExists(javaClass, "mExtras") != null) {
            XposedHelpers.setObjectField(this, "mExtras", extras)
        } else {
            extrasField?.set(this, extras)
        }
    }
}

internal fun Location.safeSetLatitude(latitude: Double) {
    XposedBridge.invokeOriginalMethod(
        setLatitudeMethod,
        this,
        arrayOf(latitude)
    )
}

internal fun Location.safeSetLongitude(longitude: Double) {
    XposedBridge.invokeOriginalMethod(
        setLongitudeMethod,
        this,
        arrayOf(longitude)
    )
}

internal fun Location.safeSetProvider(provider: String) {
    XposedBridge.invokeOriginalMethod(
        setProviderMethod,
        this,
        arrayOf(provider)
    )
}


private val getLastKnownLocationMethod by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy XposedHelpers.findMethodExactIfExists(
            LocationManager::class.java,
            "getLastKnownLocation",
            String::class.java
        ).also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

internal fun LocationManager.safeGetLastKnownLocation(provider: String): Location? {
    return XposedBridge.invokeOriginalMethod(
        getLastKnownLocationMethod,
        this,
        arrayOf(provider)
    ) as? Location
}
