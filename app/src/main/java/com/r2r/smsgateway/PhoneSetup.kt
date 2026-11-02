package com.r2r.smsgateway

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/** Phone settings that decide whether the gateway can work while it is not open. */
object PhoneSetup {
    /** Xiaomi's own "Autostart" switch (HyperOS / MIUI app op); there is no public API for it. */
    private const val OP_AUTOSTART = 10008

    fun isXiaomi(): Boolean =
        Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco") || Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

    /** "No restrictions" in the battery saver: Android's battery-optimisation exemption. */
    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /** true / false on Xiaomi phones; null when the phone has no such switch or it cannot be read. */
    fun autostart(context: Context): Boolean? {
        if (!isXiaomi()) return null
        return try {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return null
            val check = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java,
            )
            (check.invoke(ops, OP_AUTOSTART, Process.myUid(), context.packageName) as Int) == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            null // reading it was blocked; the user has to check in App info
        }
    }

    /** The name shown in the phone's settings (e.g. "Redmi Note 13 Pro 5G"), else the model. */
    fun deviceName(context: Context): String =
        Settings.Global.getString(context.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** The phone's IPv4 address on Wi-Fi, or null when it is not on Wi-Fi. */
    fun wifiIp(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
            ?: return null
        return cm.getLinkProperties(wifi)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is java.net.Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }

    /** Xiaomi's Autostart page if it can be opened, otherwise the app's App info page. */
    fun openAutostart(context: Context) {
        val autostart = Intent().setComponent(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
        )
        try {
            context.startActivity(autostart)
        } catch (e: Exception) {
            openAppInfo(context)
        }
    }

    fun openAppInfo(context: Context) {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }
}
