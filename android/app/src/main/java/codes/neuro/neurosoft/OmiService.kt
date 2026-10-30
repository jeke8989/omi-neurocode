package codes.neuro.neurosoft

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/** Фоновая служба: держит соединение с кулоном, пока телефон заблокирован. */
class OmiService : Service() {
    private var started = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Core.init(this)
        try {
            val notification = Core.buildNotification(this)
            if (Build.VERSION.SDK_INT >= 29) {
                val device = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                val withLocation = Core.prefs.locationEnabled && Core.locationPermissionGranted()
                try {
                    startForeground(Core.NOTIFICATION_ID, notification,
                        if (withLocation) device or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else device)
                } catch (e: SecurityException) {
                    // например, после перезагрузки Android не даёт геолокацию фоновому запуску — пишем без неё
                    startForeground(Core.NOTIFICATION_ID, notification, device)
                }
            } else {
                startForeground(Core.NOTIFICATION_ID, notification)
            }
            started = true
            Core.onServiceStarted()
        } catch (e: Exception) {
            Log.e("NeuroSoft", "cannot start foreground service", e)
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!started) return START_NOT_STICKY
        return START_STICKY
    }

    override fun onDestroy() {
        if (started) Core.onServiceStopped()
        super.onDestroy()
    }
}
