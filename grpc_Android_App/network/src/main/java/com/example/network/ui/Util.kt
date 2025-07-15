package com.example.network.ui

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings

/**
 * Retrieves the user-friendly device name from shared preferences, or generates a fallback name based on the device ID.
 *
 * @receiver Context used to access shared preferences and device settings.
 * @return The user-friendly device name as a String.
 */
@SuppressLint("HardwareIds")
fun Context.loadFriendlyName(): String {
    val prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
    return prefs.getString("friendly_name", null)
        ?: ("Tablet-" + Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ANDROID_ID
        ).takeLast(4))
}