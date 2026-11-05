package com.laradars.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/** All user settings, in one SharedPreferences file. */
class Prefs(context: Context) {
    private val p: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var radiusM: Int
        get() = p.getInt("radius_m", DEFAULT_RADIUS_M)
        set(v) = p.edit().putInt("radius_m", v).apply()

    var metric: Boolean
        get() = p.getBoolean("metric", false)
        set(v) = p.edit().putBoolean("metric", v).apply()

    var voice: Boolean
        get() = p.getBoolean("voice", true)
        set(v) = p.edit().putBoolean("voice", v).apply()

    var sound: Boolean
        get() = p.getBoolean("sound", true)
        set(v) = p.edit().putBoolean("sound", v).apply()

    var vibration: Boolean
        get() = p.getBoolean("vibration", true)
        set(v) = p.edit().putBoolean("vibration", v).apply()

    var onboardingDone: Boolean
        get() = p.getBoolean("onboarding_done", false)
        set(v) = p.edit().putBoolean("onboarding_done", v).apply()

    var banner: Boolean
        get() = p.getBoolean("banner", true)
        set(v) = p.edit().putBoolean("banner", v).apply()

    var bannerAsked: Boolean
        get() = p.getBoolean("banner_asked", false)
        set(v) = p.edit().putBoolean("banner_asked", v).apply()

    var onlyLive: Boolean
        get() = p.getBoolean("only_live", false)
        set(v) = p.edit().putBoolean("only_live", v).apply()

    var autoStartDriving: Boolean
        get() = p.getBoolean("auto_driving", false)
        set(v) = p.edit().putBoolean("auto_driving", v).apply()

    var autoStartBluetooth: Boolean
        get() = p.getBoolean("auto_bt", false)
        set(v) = p.edit().putBoolean("auto_bt", v).apply()

    /** MAC address of the car's Bluetooth device, empty if none chosen. */
    var bluetoothDevice: String
        get() = p.getString("bt_device", "") ?: ""
        set(v) = p.edit().putString("bt_device", v).apply()

    var bluetoothDeviceName: String
        get() = p.getString("bt_device_name", "") ?: ""
        set(v) = p.edit().putString("bt_device_name", v).apply()

    var lastDataCheckMs: Long
        get() = p.getLong("last_data_check", 0L)
        set(v) = p.edit().putLong("last_data_check", v).apply()

    var dataVersion: String
        get() = p.getString("data_version", "") ?: ""
        set(v) = p.edit().putString("data_version", v).apply()

    /** User-reported camera positions: camera id -> [lat, lng]. */
    fun reports(): MutableMap<String, DoubleArray> {
        val out = HashMap<String, DoubleArray>()
        val o = JSONObject(p.getString("reports", "{}") ?: "{}")
        for (k in o.keys()) {
            val a = o.getJSONArray(k)
            out[k] = doubleArrayOf(a.getDouble(0), a.getDouble(1))
        }
        return out
    }

    fun saveReport(cameraId: String, lat: Double, lng: Double) {
        val o = JSONObject(p.getString("reports", "{}") ?: "{}")
        o.put(cameraId, org.json.JSONArray(listOf(lat, lng)))
        p.edit().putString("reports", o.toString()).apply()
    }

    fun clearReports() = p.edit().remove("reports").apply()

    companion object {
        private const val FILE = "laradars"
        const val DEFAULT_RADIUS_M = 400
    }
}
