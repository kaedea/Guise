package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.houvven.ktx_xposed.logger.XposedLogger

/**
 * @author Kaede
 * @since  21/2/2023
 */
internal inline fun log(text: String, logToXposed: Boolean = false) {
    Log.i("Xposed.location", text)
    if (logToXposed) {
        XposedLogger.e(text)
    }
}

internal fun Location.isFakeLocation() = extras != null && extras!!.getBoolean("isFake", false)
internal fun Location.isGcj02Location() = extras != null && extras!!.getBoolean("wgs2gcj", false)

internal fun Location.isTransformable(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && provider == LocationManager.FUSED_PROVIDER) {
        return false
    }
    return true
}

internal fun Location.isReliableFused(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && provider == LocationManager.FUSED_PROVIDER) {
        if (extras != null) {
            if (extras!!.containsKey("locationType") && extras!!.getInt("locationType", -1) != 1) {
                return true
            }
        }
    }
    return false
}

/**
 * FixUps means Location has been transformed into GCJ-02 coord by Google Map.
 */
internal fun Location.isFixUps(): Boolean {
    if (javaClass != Location::class.java) {
        if (extras != null && extras!!.containsKey("isFixUps")) {
            return extras!!.getBoolean("isFixUps", false)
        }
        return toString().contains("fixups=true").also { isFixUps ->
            extras = let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("isFixUps", isFixUps)
                }
                return@bundle bundle
            }
        }
    }
    return false
}

internal fun Location.getLocationWgs84(): CoordTransform.LatLng? {
    if (extras == null) return null
    if (!extras!!.containsKey("latWgs84") || !extras!!.containsKey("lngWgs84")) return null
    return CoordTransform.LatLng(extras!!.getDouble("latWgs84"), extras!!.getDouble("lngWgs84"))
}

internal fun Location.getLocationGcj02(): CoordTransform.LatLng? {
    if (extras == null) return null
    if (!extras!!.containsKey("latGcj02") || !extras!!.containsKey("lngGcj02")) return null
    return CoordTransform.LatLng(extras!!.getDouble("latGcj02"), extras!!.getDouble("lngGcj02"))
}

internal fun Location.wgs84ToGcj02(): CoordTransform.LatLng? {
    log("wgs84ToGcj02: $this")
    synchronized(this) {
        if (!isTransformable() || isGcj02Location() || isFixUps()) {
            throw IllegalStateException("isTransformable=${isTransformable()}, isGcj02=${isGcj02Location()}, isFixUps=${isFixUps()}")
        }
        val inputLatLng = safeGetLatLng() ?: return null
        return CoordTransform.wgs84ToGcj02(inputLatLng)?.also { outputLatLng ->
            latitude = outputLatLng.latitude
            longitude = outputLatLng.longitude
            extras = let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("wgs2gcj", true)
                    putDouble("latWgs84" ,inputLatLng.latitude)
                    putDouble("lngWgs84" ,inputLatLng.longitude)
                    putDouble("latGcj02" ,outputLatLng.latitude)
                    putDouble("lngGcj02" ,outputLatLng.longitude)
                }
                return@bundle bundle
            }
            log("[${inputLatLng.latitude}, ${inputLatLng.longitude}] >> [${outputLatLng.latitude}, ${outputLatLng.longitude}]")
        }
    }
}

private val latitudeFiled by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy Location::class.java.getDeclaredField("mLatitudeDegrees").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

private val longitudeField by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    try {
        return@lazy Location::class.java.getDeclaredField("mLongitudeDegrees").also {
            it.isAccessible = true
        }
    } catch (e: Exception) {
        return@lazy null
    }
}

/**
 * Avoid recursive hooking
 */
internal fun Location.safeGetLatLng(): CoordTransform.LatLng? {
    run {
        val lat = latitudeFiled?.get(this) as? Double
        val lng = longitudeField?.get(this) as? Double
        if (lat != null && lng != null) {
            return CoordTransform.LatLng(lat, lng)
        }
    }
    val raw = toString()
    val symBgn = "Location[${provider} "
    val symEnd = " "
    if (raw.startsWith(symBgn)) {
        val prefix = raw.substring(raw.indexOf(symBgn) + symBgn.length)
        if (prefix.contains(symEnd)) {
            val target = prefix.substring(0, prefix.indexOf(symEnd))
            val split = target.split(",")
            if (split.size == 2) {
                return CoordTransform.LatLng(split[0].toDouble(), split[1].toDouble())
            }
        }
    }
    return null
}


/**
 * Copycat of [JZLocationConverter-for-Android](https://github.com/taoweiji/JZLocationConverter-for-Android)
 */
internal object CoordTransform {
    private fun LAT_OFFSET_0(x: Double, y: Double): Double {
        return -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y + 0.2 * Math.sqrt(Math.abs(x))
    }

    private fun LAT_OFFSET_1(x: Double, y: Double): Double {
        return (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    }

    private fun LAT_OFFSET_2(x: Double, y: Double): Double {
        return (20.0 * Math.sin(y * Math.PI) + 40.0 * Math.sin(y / 3.0 * Math.PI)) * 2.0 / 3.0
    }

    private fun LAT_OFFSET_3(x: Double, y: Double): Double {
        return (160.0 * Math.sin(y / 12.0 * Math.PI) + 320 * Math.sin(y * Math.PI / 30.0)) * 2.0 / 3.0
    }

    private fun LON_OFFSET_0(x: Double, y: Double): Double {
        return 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y + 0.1 * Math.sqrt(Math.abs(x))
    }

    private fun LON_OFFSET_1(x: Double, y: Double): Double {
        return (20.0 * Math.sin(6.0 * x * Math.PI) + 20.0 * Math.sin(2.0 * x * Math.PI)) * 2.0 / 3.0
    }

    private fun LON_OFFSET_2(x: Double, y: Double): Double {
        return (20.0 * Math.sin(x * Math.PI) + 40.0 * Math.sin(x / 3.0 * Math.PI)) * 2.0 / 3.0
    }

    private fun LON_OFFSET_3(x: Double, y: Double): Double {
        return (150.0 * Math.sin(x / 12.0 * Math.PI) + 300.0 * Math.sin(x / 30.0 * Math.PI)) * 2.0 / 3.0
    }

    private const val RANGE_LON_MAX = 137.8347
    private const val RANGE_LON_MIN = 72.004
    private const val RANGE_LAT_MAX = 55.8271
    private const val RANGE_LAT_MIN = 0.8293

    private const val jzA = 6378245.0
    private const val jzEE = 0.00669342162296594323

    fun transformLat(x: Double, y: Double): Double {
        var ret = LAT_OFFSET_0(x, y)
        ret += LAT_OFFSET_1(x, y)
        ret += LAT_OFFSET_2(x, y)
        ret += LAT_OFFSET_3(x, y)
        return ret
    }

    fun transformLon(x: Double, y: Double): Double {
        var ret = LON_OFFSET_0(x, y)
        ret += LON_OFFSET_1(x, y)
        ret += LON_OFFSET_2(x, y)
        ret += LON_OFFSET_3(x, y)
        return ret
    }

    fun outOfChina(lat: Double, lon: Double): Boolean {
        if (lon < RANGE_LON_MIN || lon > RANGE_LON_MAX) return true
        return if (lat < RANGE_LAT_MIN || lat > RANGE_LAT_MAX) true else false
    }

    fun gcj02Encrypt(ggLat: Double, ggLon: Double): LatLng {
        val resPoint = LatLng()
        val mgLat: Double
        val mgLon: Double
        if (outOfChina(ggLat, ggLon)) {
            resPoint.latitude = ggLat
            resPoint.longitude = ggLon
            return resPoint
        }
        var dLat = transformLat(ggLon - 105.0, ggLat - 35.0)
        var dLon = transformLon(ggLon - 105.0, ggLat - 35.0)
        val radLat = ggLat / 180.0 * Math.PI
        var magic = Math.sin(radLat)
        magic = 1 - jzEE * magic * magic
        val sqrtMagic = Math.sqrt(magic)
        dLat = dLat * 180.0 / (jzA * (1 - jzEE) / (magic * sqrtMagic) * Math.PI)
        dLon = dLon * 180.0 / (jzA / sqrtMagic * Math.cos(radLat) * Math.PI)
        mgLat = ggLat + dLat
        mgLon = ggLon + dLon
        resPoint.latitude = mgLat
        resPoint.longitude = mgLon
        return resPoint
    }

    fun gcj02Decrypt(gjLat: Double, gjLon: Double): LatLng? {
        val gPt = gcj02Encrypt(gjLat, gjLon)
        val dLon = gPt.longitude - gjLon
        val dLat = gPt.latitude - gjLat
        val pt = LatLng()
        pt.latitude = gjLat - dLat
        pt.longitude = gjLon - dLon
        return pt
    }

    fun bd09Decrypt(bdLat: Double, bdLon: Double): LatLng {
        val gcjPt = LatLng()
        val x = bdLon - 0.0065
        val y = bdLat - 0.006
        val z = Math.sqrt(x * x + y * y) - 0.00002 * Math.sin(y * Math.PI)
        val theta = Math.atan2(y, x) - 0.000003 * Math.cos(x * Math.PI)
        gcjPt.longitude = z * Math.cos(theta)
        gcjPt.latitude = z * Math.sin(theta)
        return gcjPt
    }

    fun bd09Encrypt(ggLat: Double, ggLon: Double): LatLng? {
        val bdPt = LatLng()
        val z = Math.sqrt(ggLon * ggLon + ggLat * ggLat) + 0.00002 * Math.sin(
            ggLat * Math.PI
        )
        val theta = Math.atan2(ggLat, ggLon) + 0.000003 * Math.cos(ggLon * Math.PI)
        bdPt.longitude = z * Math.cos(theta) + 0.0065
        bdPt.latitude = z * Math.sin(theta) + 0.006
        return bdPt
    }

    /**
     * @param location 世界标准地理坐标(WGS-84)
     * @return 中国国测局地理坐标（GCJ-02）<火星坐标>
     * @brief 世界标准地理坐标(WGS-84) 转换成 中国国测局地理坐标（GCJ-02）<火星坐标>
     *
     * ####只在中国大陆的范围的坐标有效，以外直接返回世界标准坐标
    </火星坐标></火星坐标> */
    fun wgs84ToGcj02(location: LatLng): LatLng? {
        return gcj02Encrypt(location.latitude, location.longitude)
    }

    /**
     * @param location 中国国测局地理坐标（GCJ-02）
     * @return 世界标准地理坐标（WGS-84）
     * @brief 中国国测局地理坐标（GCJ-02） 转换成 世界标准地理坐标（WGS-84）
     *
     * ####此接口有1－2米左右的误差，需要精确定位情景慎用
     */
    fun gcj02ToWgs84(location: LatLng): LatLng? {
        return gcj02Decrypt(location.latitude, location.longitude)
    }

    /**
     * @param location 世界标准地理坐标(WGS-84)
     * @return 百度地理坐标（BD-09)
     * @brief 世界标准地理坐标(WGS-84) 转换成 百度地理坐标（BD-09)
     */
    fun wgs84ToBd09(location: LatLng): LatLng? {
        val gcj02Pt = gcj02Encrypt(location.latitude, location.longitude)
        return bd09Encrypt(gcj02Pt.latitude, gcj02Pt.longitude)
    }

    /**
     * @param location 中国国测局地理坐标（GCJ-02）<火星坐标>
     * @return 百度地理坐标（BD-09)
     * @brief 中国国测局地理坐标（GCJ-02）<火星坐标> 转换成 百度地理坐标（BD-09)
    </火星坐标></火星坐标> */
    fun gcj02ToBd09(location: LatLng): LatLng? {
        return bd09Encrypt(location.latitude, location.longitude)
    }

    /**
     * @param location 百度地理坐标（BD-09)
     * @return 中国国测局地理坐标（GCJ-02）<火星坐标>
     * @brief 百度地理坐标（BD-09) 转换成 中国国测局地理坐标（GCJ-02）<火星坐标>
    </火星坐标></火星坐标> */
    fun bd09ToGcj02(location: LatLng): LatLng {
        return bd09Decrypt(location.latitude, location.longitude)
    }

    /**
     * @param location 百度地理坐标（BD-09)
     * @return 世界标准地理坐标（WGS-84）
     * @brief 百度地理坐标（BD-09) 转换成 世界标准地理坐标（WGS-84）
     *
     * ####此接口有1－2米左右的误差，需要精确定位情景慎用
     */
    fun bd09ToWgs84(location: LatLng): LatLng? {
        val gcj02 = bd09ToGcj02(location)
        return gcj02Decrypt(gcj02.latitude, gcj02.longitude)
    }

    class LatLng {
        var latitude = 0.0
        var longitude = 0.0

        constructor(latitude: Double, longitude: Double) {
            this.latitude = latitude
            this.longitude = longitude
        }

        constructor() {}
    }
}

