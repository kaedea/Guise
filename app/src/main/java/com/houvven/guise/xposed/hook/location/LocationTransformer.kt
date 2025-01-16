package com.houvven.guise.xposed.hook.location

import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import com.houvven.ktx_xposed.logger.exit
import com.houvven.ktx_xposed.logger.logcatInfo
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * @author Kaede
 * @since  21/2/2023
 */

private const val CHECK_PASSIVE_LOCATION_RELIABLE = false
private const val UPDATE_ACCURACY = false
private const val BOUNDING_REFRESH_MS = 10 * 60 * 1000L // 10min
internal const val LOCATION_MOVE_DISTANCE_TOLERANCE = 20  // 20m

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
        val limit = 200
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
        return it.latLngHashcode()
    }
    return hashCode()
}

internal fun Location.isFakeLocation() = extras != null && extras!!.getBoolean("isFake", false)

internal fun Location.isGcj02Location(): Boolean {
    safeGetLatLng()?.let {
        synchronized (mGcj02Holder) {
            val hashcode = it.latLngHashcode()
            val hit = mGcj02Holder.contains(hashcode)
            logcatInfo { "\tgetLatLngHashcode: ${it.toSimpleString()}@hash=$hashcode, hit=${hit}" }
            if (hit) {
                return true
            }
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

internal fun Location.isTransformable(lastLatLng: CoordTransform.LatLng? = null): Boolean {
    val provider = safeGetProvider()
    if (provider == LocationManager.GPS_PROVIDER) {
        return true
    }
    if (provider == LocationManager.NETWORK_PROVIDER) {
        // fromPassive=true means that this location is not retrieved by LocationListener.
        // Tt could be produced by LocationListener of other process.
        // In this case we consider this type of network location as not-transformable, which we should
        // check if reliable or not.
        // return !fromPassive

        // Check if near to last gcj-02
        if (lastLatLng != null) {
            safeGetLatLng()?.let {
                if (it.isNearTo(lastLatLng)) {
                    return false
                }
            }
        }
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
    // A reliable fused location means:
    //   1. the location has been transformed into gcj02
    //   2. should transform again
    //   3. avoid to apply unreliable fused location
    logcatInfo { "isReliableFused: last=$lastLatLng" }
    if (!CHECK_PASSIVE_LOCATION_RELIABLE) {
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
        if (extras?.containsKey("locationType") == true && extras.getInt("locationType", -1) != 3) {
            logcatInfo { "\tno: locationType=${extras.containsKey("locationType")}" }
        } else {
            if (!isFixUps()) {
                reliable = true
                // Check if suddenly drifting
                if (lastLatLng != null) {
                    safeGetLatLng()?.let {
                        val delta = it.toDistance(lastLatLng)
                        logcatInfo { "\tmove: [${lastLatLng.latitude}, ${lastLatLng.longitude}] >> [${it.latitude},${it.longitude}] = $delta" }
                        if (abs(delta) <= 200.0) {
                            logcatInfo { "\tyes: moving" }
                        } else {
                            // Maybe just move too fast
                            safeGetLatLng()?.let {
                                CoordTransform.wgs84ToGcj02(lastLatLng)?.let { twiceTransLatLng ->
                                    if (twiceTransLatLng.isNearTo(it)) {
                                        logcatInfo { "\tno: drifting, twiceTransDistance=${twiceTransLatLng.toDistance(it)}" }
                                        reliable = false
                                    } else {
                                        logcatInfo { "\tyes: moving fast, twiceTransDistance=${twiceTransLatLng.toDistance(it)}" }
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
    logcatInfo { "\treliable: $reliable" }
    return reliable
}

internal fun Location.tryReverseTransform(lastLatLng: CoordTransform.LatLng, tolerance: Int): CoordTransform.LatLng? {
    if (isTransformedFrom(lastLatLng, tolerance)) {
        safeGetLatLng()?.let {
            return CoordTransform.gcj02ToWgs84(it)
        }
    }
    return null
}

internal fun Location.isTransformedFrom(fromLatLng: CoordTransform.LatLng, tolerance: Int): Boolean {
    safeGetLatLng()?.let {
        CoordTransform.wgs84ToGcj02(fromLatLng)?.let { toLatLng ->
            return abs(toLatLng.toDistance(it)) <= tolerance
        }
    }
    return false
}

/**
 * FixUps means Location has been transformed into GCJ-02 coord by Google Map.
 */
internal fun Location.isFixUps(): Boolean {
    if (javaClass != Location::class.java) {
        val extras = safeGetExtras()
        if (extras != null && extras.containsKey("isFixUps")) {
            return extras.getBoolean("isFixUps", false)
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

internal fun Location.wgs84ToGcj02(readOnly: Boolean = true): Pair<CoordTransform.LatLng, CoordTransform.LatLng>? { // <wgs84, gcj02>
    logcatInfo { "wgs84ToGcj02@${myHashcode()}: $this" }
    if (/*!isTransformable() || */isGcj02Location() || isFixUps()) {
        exit { throw IllegalStateException("isTransformable=${isTransformable()}, isGcj02=${isGcj02Location()}, isFixUps=${isFixUps()}") }
    }
    val oldLatLng = safeGetLatLng()
    val newLatLng = oldLatLng?.let {
        return@let CoordTransform.wgs84ToGcj02(oldLatLng)?.also { newLatLng ->
            synchronized(mGcj02Holder) {
                val hashcode = newLatLng.latLngHashcode()
                logcatInfo { "\tputLatLngHashcode: ${newLatLng.toSimpleString()}@hash=$hashcode" }
                mGcj02Holder.add(hashcode)
            }
            newLatLng.setTimes(safeGetTime(), safeGetElapsedRealtimeNanos())
            if (safeHasSpeed() && safeHasBearing()) {
                newLatLng.setSpeedAndBearing(safeGetSpeed(), safeGetBearing())
            }
            safeSetExtras(let bundle@{
                val bundle = it.safeGetExtras() ?: Bundle()
                bundle.run {
                    putDouble("latWgs84" ,oldLatLng.latitude)
                    putDouble("lngWgs84" ,oldLatLng.longitude)
                    putDouble("latGcj02" ,newLatLng.latitude)
                    putDouble("lngGcj02" ,newLatLng.longitude)
                }
                return@bundle bundle
            })

            if (UPDATE_ACCURACY) {
                accuracy += oldLatLng.toDistance(newLatLng)
            }
            if (!readOnly) {
                markAsGcj02(newLatLng)
            }
        }
    }
    logcatInfo { "\t${oldLatLng?.toSimpleString()}>>${newLatLng?.toSimpleString()}" }
    if (oldLatLng != null && newLatLng != null) {
        logcatInfo { "\tdelta: ${oldLatLng.toDistance(newLatLng)}" }
        CoordTransform.gcj02ToWgs84(newLatLng)?.let { reverse ->
            logcatInfo { "\treverse<<${reverse.toSimpleString()}, delta: ${reverse.toDistance(oldLatLng)}" }
        }
    }
    logcatInfo { "cj02@${myHashcode()}: $this" }
    return if (oldLatLng == null || newLatLng == null) {
        null
    } else {
        Pair(oldLatLng, newLatLng)
    }
}

internal fun Location.markAsGcj02(newLatLng: CoordTransform.LatLng) {
    safeSetExtras(let bundle@{
        val bundle = it.safeGetExtras() ?: Bundle()
        bundle.run {
            putBoolean("wgs2gcj", true)
        }
        return@bundle bundle
    })

    safeSetLatLng(newLatLng)

    // refreshing all?
    set(Location(this))

    val originProvider = safeGetProvider()
    safeSetProvider("${originProvider}@gcj02")

    // Try remove speed & bearing after transformed to avoid fused chaos?
    // if (newLatLng.hasSpeedAndBearing) {
    //     removeSpeed()
    //     removeBearing()
    //     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
    //         removeSpeedAccuracy()
    //         removeBearingAccuracy()
    //     }
    // }
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
        companion object {
            fun roundToDecimalPlaces(input: Double, place: Int = 7): Double {
                return BigDecimal(input.toString()).setScale(place, RoundingMode.HALF_EVEN).toDouble()
            }

            fun latLngHashcode(latitude: Double, longitude: Double) = Objects.hash(latitude, longitude)

            fun normalizeTo360Bearing(bearing: Float): Float {
                // Normalize bearings ([0, 360) or (-180, 180)) to the range [0, 360)
                return (bearing + 360) % 360
            }

            fun is360BearingClose(bearingLeft: Float, bearingRight: Float, threshold: Float): Boolean {
                // Check if the difference is within the threshold
                return relative360BearingDelta(bearingLeft, bearingRight) <= threshold
            }

            fun relative360BearingDelta(bearingLeft: Float, bearingRight: Float): Float {
                // Calculate the absolute difference
                val diff = abs(bearingLeft - bearingRight)
                // Take the smaller angle on the circle
                return diff.coerceAtMost(360 - diff)
            }
        }

        var latitude = 0.0
            set(value) {
                field = roundToDecimalPlaces(value, 7)
            }
        var longitude = 0.0
            set(value) {
                field = roundToDecimalPlaces(value, 7)
            }

        var hasTimes = false
        var timeMs = 0L
        var elapsedRealtimeNanos = 0L

        var hasSpeedAndBearing = false
        var speed = 0F
        var bearing = 0F

        constructor(latitude: Double, longitude: Double) {
            this.latitude = latitude
            this.longitude = longitude
        }

        constructor()

        fun setTimes(time: Long, elapsedRealtimeNanos: Long) {
            this.hasTimes = true
            this.timeMs = time
            this.elapsedRealtimeNanos = elapsedRealtimeNanos
        }

        fun setSpeedAndBearing(speed: Float, bearing: Float) {
            this.hasSpeedAndBearing = true
            this.speed = speed
            this.bearing = bearing
        }

        override fun equals(other: Any?): Boolean {
            if (other is LatLng) {
                return latitude == other.latitude && longitude == other.longitude
            }
            return false
        }

        fun latLngHashcode() = latLngHashcode(latitude, longitude)

        override fun toString(): String {
            val timeAgoSec = if (hasTimes) "${TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - timeMs)}s" else "null"
            val speedTips = if (hasSpeedAndBearing) "${speed}mPs" else "null"
            val bearingTips = if (hasSpeedAndBearing) "${bearing}degree" else "null"
            return "LatLng([$latitude,$longitude], timeAgoSec=$timeAgoSec, speed=$speedTips, bearing=$bearingTips)"
        }

        fun toSimpleString() = "[$latitude,$longitude]"

        fun isExpired(thresholdMs: Long, currMs: Long = System.currentTimeMillis(), currElapsedRealtimeNanos: Long = SystemClock.elapsedRealtimeNanos()) =
            // Better way to handle hasTimes=false ?
            !hasTimes
                    || (timeMs < currMs && currMs - timeMs > thresholdMs)
                    || (elapsedRealtimeNanos < currElapsedRealtimeNanos && TimeUnit.NANOSECONDS.toMillis( currElapsedRealtimeNanos - elapsedRealtimeNanos) > thresholdMs)

        fun isNearTo(another: LatLng) = abs(toDistance(another)) <= LOCATION_MOVE_DISTANCE_TOLERANCE

        fun toDistance(end: LatLng): Float {
            val floats = floatArrayOf(-1f)
            Location.distanceBetween(this.latitude, this.longitude, end.latitude, end.longitude, floats)
            return floats[0].coerceAtLeast(0F)
        }

        /**
         * Should check if returning null
         */
        fun speedMpsFrom(start: LatLng): Float? {
            if (!this.hasTimes || !start.hasTimes) {
                return null
            }
            val sec = TimeUnit.NANOSECONDS.toSeconds(abs(this.elapsedRealtimeNanos - start.elapsedRealtimeNanos))
            if (sec <= 0) {
                return null
            }
            val distance = toDistance(start)
            return distance / sec
        }

        fun bearingToIn360Degree(location: Location): Float {
            return Location("bearing").also { it.safeSetLatLng(this) }.bearingTo(location).let { bearingToIn180Degree ->
                return@let normalizeTo360Bearing(bearingToIn180Degree)
            }
        }
    }
}
