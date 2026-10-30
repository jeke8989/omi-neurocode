package codes.neuro.neurosoft.diary

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log

/**
 * Геолокация для дневника: только пока идёт запись и только если включено в настройках.
 * Точка не чаще раза в 3 минуты или при сдвиге на 75 м — батарея почти не страдает.
 */
class LocationTracker(context: Context, private val onLocation: (Location) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private var active = false

    // Реализуем все методы явно: на Android < 11 у LocationListener нет реализаций по умолчанию.
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onLocation(location)
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (active || manager == null) return
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 3 * 60_000L, 75f, listener, Looper.getMainLooper())
                    active = true
                }
            }
        } catch (e: SecurityException) {
            Log.w("NeuroSoft.Location", "no permission", e)
        }
    }

    fun stop() {
        if (!active) return
        try {
            manager?.removeUpdates(listener)
        } catch (_: Exception) {
        }
        active = false
    }
}
