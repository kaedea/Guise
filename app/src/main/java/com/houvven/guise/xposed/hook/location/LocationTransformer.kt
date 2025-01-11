package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import com.houvven.ktx_xposed.logger.logcat
import com.houvven.ktx_xposed.logger.logcatInfo
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.*
import java.util.concurrent.Executors

/**
 * @author Kaede
 * @since  21/2/2023
 */

private const val BOUNDING_REFRESH_MS = 10 * 60 * 1000L
private var mBoundingRef: Pair<Boolean, Long> = Pair(true, 0L)
internal fun Location.checkIfBounded(): Boolean {
    safeGetLatLng()?.let {
        return ChinaMapBounding.isInMainland(it.latitude, it.longitude)
                && !ChinaMapBounding.isInHongkong(it.latitude, it.longitude)
                && !ChinaMapBounding.isInMacao(it.latitude, it.longitude)
                && !ChinaMapBounding.isInTaiwan(it.latitude, it.longitude)
    }
    return false
}

private val mGcj02Holder by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
    return@lazy object : LinkedHashSet<Int>() {
        val limit = 100
        override fun add(element: Int): Boolean {
            val add = super.add(element)
            if (add && size >= limit) {
                iterator().apply {
                    next()
                    remove()
                }
            }
            return add
        }
    }
}
internal fun Location.myHashcode(): Int {
    safeGetLatLng()?.let {
        return Objects.hash(safeGetProvider(), it.longitude, it.latitude)
    }
    return hashCode()
}
internal fun Location.isFakeLocation() = extras != null && extras!!.getBoolean("isFake", false)
internal fun Location.isGcj02Location(): Boolean {
    synchronized (mGcj02Holder) {
        if (mGcj02Holder.contains(myHashcode())) {
            return true
        }
    }
    if (safeGetProvider()?.endsWith("@gcj02") == true) {
        return true
    }
    return extras != null && extras!!.getBoolean("wgs2gcj", false)
}

internal fun Location.shouldTransform(): Boolean {
    val shouldRefreshing = mBoundingRef.second <= 0L || System.currentTimeMillis() - mBoundingRef.second >= BOUNDING_REFRESH_MS
    if (shouldRefreshing) {
        logcatInfo { "checkIfBounded: post" }
        Executors.newCachedThreadPool().execute {
            mBoundingRef = Pair(checkIfBounded(), System.currentTimeMillis())
            logcatInfo { "checkIfBounded: done, bounded=${mBoundingRef.first}" }
        }
    }
    return mBoundingRef.first
}

internal fun Location.isTransformable(): Boolean {
    if (safeGetProvider() == LocationManager.GPS_PROVIDER || safeGetProvider() == LocationManager.NETWORK_PROVIDER) {
        return true
    }
    // if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && provider == LocationManager.FUSED_PROVIDER) {
    //     // Fused Location (both Location & GmmLocation) might has been transformed
    //     // Should not be transformed again
    //     return false
    // }
    return false
}

internal fun Location.isReliableFused(lastLatLng: CoordTransform.LatLng? = null): Boolean {
    logcatInfo { "isReliableFused: last=$lastLatLng" }
    if (!CHECK_FUSE_RELIABLE) {
        return true
    }
    var reliable = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && safeGetProvider() == LocationManager.FUSED_PROVIDER) {
        // Fused Location might has been transformed twice: We transformed it and then GMS did it again
        // In this case, we give up this location, or reverse it backward.
        // Likely twice-transformed:
        //  - locationType=1
        //  - fixups=true
        //  - Suddenly drifting of about 629 meters (diff of wgs-84 & gcj-02 in the same real position)
        // Only god knows how do the following codes work!
        val extras = safeGetExtras()
        if (extras?.containsKey("locationType") == true && extras!!.getInt("locationType", -1) != 3) {
            logcatInfo { "\tno: locationType=${extras!!.containsKey("locationType")}" }
        } else {
            if (!isFixUps()) {
                reliable = true
                // Check if suddenly drifting
                if (lastLatLng != null) {
                    safeGetLatLng()?.let {
                        val delta = it.toDistance(lastLatLng)
                        logcatInfo { "\tmove: [${lastLatLng.latitude}, ${lastLatLng.longitude}] >> [${it.latitude},${it.longitude}] = $delta" }
                        if (delta in 0.0..200.0) {
                            logcatInfo { "\tyes: moving" }
                        } else {
                            // Maybe just move too fast
                            safeGetLatLng()?.let {
                                CoordTransform.wgs84ToGcj02(lastLatLng)?.let { twiceTransLatLng ->
                                    val distance = twiceTransLatLng.toDistance(it)
                                    if (distance in 0.0..10.0) {
                                        logcatInfo { "\tno: drifting, twiceTransDistance=$distance" }
                                        reliable = false
                                    } else {
                                        logcatInfo { "\tyes: moving fast, twiceTransDistance=$distance" }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                logcatInfo { "\tno: fixups" }
            }
        }
    } else {
        logcatInfo { "\tno: provider=${safeGetProvider()}" }
    }
    return reliable
}

internal fun Location.tryReverseTransform(lastLatLng: CoordTransform.LatLng?): CoordTransform.LatLng? {
    if (lastLatLng != null) {
        safeGetLatLng()?.let {
            CoordTransform.wgs84ToGcj02(lastLatLng)?.let { twiceTransLatLng ->
                if (twiceTransLatLng.toDistance(it) in 0.0..10.0) {
                    return CoordTransform.gcj02ToWgs84(it)
                }
            }
        }
    }
    return null
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
            safeSetExtras(let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("isFixUps", isFixUps)
                }
                return@bundle bundle
            })
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
    logcatInfo { "wgs84ToGcj02@${myHashcode()}: $this" }
    if (!isTransformable() || isGcj02Location() || isFixUps()) {
        throw IllegalStateException("isTransformable=${isTransformable()}, isGcj02=${isGcj02Location()}, isFixUps=${isFixUps()}")
    }
    val oldLatLng = safeGetLatLng()
    val newLatLng = oldLatLng?.let {
        return@let CoordTransform.wgs84ToGcj02(oldLatLng)?.also { outputLatLng ->
            safeSetLatLng(outputLatLng)
            safeSetExtras(let bundle@{
                val bundle = if (it.extras != null) it.extras else Bundle()
                bundle!!.run {
                    putBoolean("wgs2gcj", true)
                    putDouble("latWgs84" ,oldLatLng.latitude)
                    putDouble("lngWgs84" ,oldLatLng.longitude)
                    putDouble("latGcj02" ,outputLatLng.latitude)
                    putDouble("lngGcj02" ,outputLatLng.longitude)
                }
                return@bundle bundle
            })

            // if (provider in listOf(LocationManager.GPS_PROVIDER)) {
            //     accuracy = 10.0f
            // }
            // // provider = "${provider}@gcj02"
            // time = System.currentTimeMillis()
            // elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            // set(Location(this))

            val originProvider = safeGetProvider()
            safeSetProvider("${originProvider}@gcj02")

            synchronized(mGcj02Holder) {
                mGcj02Holder.add(myHashcode())
            }
        }
    }
    logcatInfo { "\t[${oldLatLng?.latitude}, ${oldLatLng?.longitude}] >> [${newLatLng?.latitude}, ${newLatLng?.longitude}]" }
    if (oldLatLng != null && newLatLng != null) {
        logcatInfo { "\tdelta: ${oldLatLng.toDistance(newLatLng)}" }
        CoordTransform.gcj02ToWgs84(newLatLng)?.let { revert ->
            logcatInfo { "\t[${revert.latitude}, ${revert.longitude}] reverse delta: ${revert.toDistance(oldLatLng)}" }
        }
    }
    logcatInfo { "cj02@${myHashcode()}: $this" }
    return newLatLng
}

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
                return CoordTransform.LatLng(lat, lng)
            }
        }
    }
    run {
        val lat = latitudeFiled?.get(this) as? Double
        val lng = longitudeField?.get(this) as? Double
        if (lat != null && lng != null) {
            return CoordTransform.LatLng(lat, lng)
        }
    }
    val raw = toString()
    val symBgn = "Location[${safeGetProvider()} "
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
        var time = 0L
        var elapsedRealtimeNanos = 0L

        constructor(latitude: Double, longitude: Double) {
            this.latitude = latitude
            this.longitude = longitude
        }

        constructor()

        fun setTimes(time: Long, elapsedRealtimeNanos: Long) {
            this.time = time
            this.elapsedRealtimeNanos = elapsedRealtimeNanos
        }

        override fun equals(other: Any?): Boolean {
            if (other is LatLng) {
                return latitude == other.latitude && longitude == other.longitude
            }
            return false
        }

        override fun toString(): String {
            return "LatLng(latitude=$latitude, longitude=$longitude, time=$time, elapsedRealtimeNanos=$elapsedRealtimeNanos)"
        }

        fun toDistance(end: LatLng): Float {
            val floats = floatArrayOf(-1f)
            Location.distanceBetween(this.latitude, this.longitude, end.latitude, end.longitude, floats)
            return floats[0]
        }
    }
}

