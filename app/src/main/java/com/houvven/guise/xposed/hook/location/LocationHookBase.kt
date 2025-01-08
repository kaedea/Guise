package com.houvven.guise.xposed.hook.location

import android.location.*
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.telephony.TelephonyManager.INCLUDE_LOCATION_DATA_NONE
import android.telephony.gsm.GsmCellLocation
import com.houvven.guise.xposed.config.ModuleConfig
import com.houvven.ktx_xposed.hook.*


internal const val HOOK_LOCATION_LISTENER = true
internal const val CHECK_FUSE_RELIABLE = false

@Suppress("DEPRECATION")
abstract class LocationHookBase(open val config: ModuleConfig) {

    abstract fun start()

    protected fun makeTelLocationFail() {
        TelephonyManager::class.java.run {
            setSomeSameNameMethodResult(
                "getCellLocation",
                "getAllCellInfo",
                "getNeighboringCellInfo",
                "getLastKnownCellIdentity",
                value = null
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setMethodResult(
                    methodName = "getLocationData",
                    value = INCLUDE_LOCATION_DATA_NONE
                )
            }
        }

        PhoneStateListener::class.java
            .setSomeSameNameMethodResult(
                "onCellLocationChanged",
                "onCellInfoChanged",
                value = null
            )

    }

    protected fun makeWifiLocationFail() {
        WifiManager::class.java.run {
            setMethodResult("getScanResults", emptyList<ScanResult>())
            setMethodResult("isWifiEnabled", false)
            setMethodResult("isScanAlwaysAvailable", false)
            setMethodResult("getWifiState", WifiManager.WIFI_STATE_DISABLED)
        }
        WifiInfo::class.java.run {
            setMethodResult("getMacAddress", "00:00:00:00:00:00")
            setMethodResult("getBSSID", "00:00:00:00:00:00")
        }
    }

    protected fun makeCellLocationFail() {
        GsmCellLocation::class.java.run {
            setMethodResult("getPsc", -1)
            setMethodResult("getLac", -1)
        }
    }

    protected fun removeNmeaListener() {
        LocationManager::class.java.setAllMethodResult("addNmeaListener", false)
    }
}
