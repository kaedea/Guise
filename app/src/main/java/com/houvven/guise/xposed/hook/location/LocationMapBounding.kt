package com.houvven.guise.xposed.hook.location

import kotlin.math.abs

@Suppress("SpellCheckingInspection", "MemberVisibilityCanBePrivate")
internal object ChinaMapBounding {
    internal data class POINT(val latitude: Double, val longitude: Double) {
        override fun toString() = "($latitude, $longitude)"
    }

    internal val mainlandPolygon by lazy {
        listOf(
            POINT(53.6674971, 122.6939719),
            POINT(53.150137, 125.9569114),
            POINT(49.9477627, 127.9674094),
            POINT(49.1278927, 130.7030051),
            POINT(47.978972, 131.3731712),
            POINT(48.5621472, 135.2156394),
            POINT(44.983056, 133.2085988),
            POINT(44.7104327, 131.4837453),
            POINT(42.2073959, 130.8247168),
            POINT(38.4930728, 122.9110708),
            POINT(36.9767757, 122.7257817),
            POINT(35.0035663, 119.9132817),
            POINT(29.7935805, 123.1280325),
            POINT(24.3887128, 119.4194098),
            POINT(22.3758009, 116.2651975),
            POINT(20.9566154, 110.9807592),
            POINT(19.5963275, 111.3213354),
            POINT(17.8588301, 109.6404272),
            POINT(18.6441328, 108.07468),
            POINT(20.2711127, 108.6240723),
            POINT(20.9798752, 107.8394024),
            POINT(22.2406116, 105.6113712),
            POINT(20.2967692, 101.3575976),
            POINT(23.5689176, 96.9482808),
            POINT(27.1275379, 97.6170537),
            POINT(26.792861, 89.5123437),
            POINT(28.2681686, 82.2030291),
            POINT(31.7165817, 77.5622282),
            POINT(39.1840324, 72.5960537),
            POINT(50.1782642, 86.9640718),
            POINT(42.5590564, 104.8053581),
            POINT(46.8752636, 114.199807),
            POINT(49.8508726, 116.4163017),
            POINT(53.0518996, 119.7012858),
        )
    }

    internal val hongkongPolygon by lazy {
        listOf(
            POINT(22.3883274, 113.8400876),
            POINT(22.3028391, 113.8065851),
            POINT(22.154, 113.837),
            POINT(22.154, 114.433),
            POINT(22.533, 114.433),
            POINT(22.5649639, 114.3031988),
            POINT(22.5495866, 114.2500695),
            POINT(22.5581474, 114.1700753),
            POINT(22.5305213, 114.1118966),
            POINT(22.5336923, 114.0925847),
            POINT(22.5041186, 114.0527592),
            POINT(22.508298, 114.0080578),
        )
    }

    internal val macaoPolygon by lazy {
        listOf(
            POINT(22.217034, 113.528164),
            POINT(22.109142, 113.528164),
            POINT(22.109142, 113.598861),
            POINT(22.217034, 113.598861),
        )
    }

    internal val taiwanPolygon by lazy {
        listOf(
            POINT(25.6407732, 120.8085288),
            POINT(23.833, 119.3),
            POINT(23.183, 119.3),
            POINT(21.281146, 120.1107744),
            POINT(21.8734357, 122.0115783),
            POINT(25.1709126, 122.341084),
        )
    }

    internal val kinmenPolygon by lazy {
        listOf(
            POINT(24.4645372, 118.2188829),
            POINT(24.3896902, 118.1404037),
            POINT(24.2986024, 118.280682),
            POINT(24.3574113, 118.5597336),
            POINT(24.555282, 118.4927905),
        )
    }

    internal val matsuPolygon by lazy {
        listOf(
            POINT(26.3009417, 119.932271),
            POINT(26.0876682, 119.8321939),
            POINT(25.8512588, 119.9503167),
            POINT(26.4012311, 120.6163596),
            POINT(26.3974008, 120.0850543),
        )
    }

    internal val wuqiuPolygon by lazy {
        listOf(
            POINT(25.0198348, 119.4303491),
            POINT(24.9470142, 119.4325806),
            POINT(24.9849857, 119.5206429),
        )
    }

    fun isInMainland(latitude: Double, longitude: Double) =
        isPointInPolygon(POINT(latitude, longitude), mainlandPolygon)

    fun isInHongkong(latitude: Double, longitude: Double) =
        isPointInPolygon(POINT(latitude, longitude), hongkongPolygon)

    fun isInMacao(latitude: Double, longitude: Double) =
        isPointInPolygon(POINT(latitude, longitude), macaoPolygon)

    fun isInTaiwan(latitude: Double, longitude: Double) =
        POINT(latitude, longitude).let {
            isPointInPolygon(it, taiwanPolygon)
                    || isPointInPolygon(it, kinmenPolygon)
                    || isPointInPolygon(it, matsuPolygon)
                    || isPointInPolygon(it, wuqiuPolygon)
        }

    internal fun isPointInPolygon(point: POINT, polygon: List<POINT>): Boolean {
        var intersectCount = 0
        val precision = 2e-10 // 浮点数比较的精度

        for (i in polygon.indices) {
            val p1 = polygon[i]
            val p2 = polygon[(i + 1) % polygon.size]

            // 检查点是否在多边形的边上
            if (point == p1 || point == p2) {
                return true
            }

            // 检查水平线是否穿过边
            if (point.latitude in minOf(p1.latitude, p2.latitude)..maxOf(p1.latitude, p2.latitude)) {
                // 计算交点的经度
                val intersectLongitude = if (p1.latitude != p2.latitude) {
                    p1.longitude + (point.latitude - p1.latitude) * (p2.longitude - p1.longitude) / (p2.latitude - p1.latitude)
                } else {
                    point.longitude
                }

                // 检查点是否在边上
                if (abs(point.longitude - intersectLongitude) < precision) {
                    return true
                }

                // 检查交点在点的右侧
                if (point.longitude < intersectLongitude) {
                    intersectCount++
                }
            }
        }

        // 如果交点数为奇数，则点在多边形内
        return intersectCount % 2 == 1
    }
}
