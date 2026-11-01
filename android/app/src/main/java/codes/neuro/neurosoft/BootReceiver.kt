package codes.neuro.neurosoft

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Включает запись после перезагрузки телефона и после обновления приложения. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(context)
        if (!prefs.autoStart || prefs.deviceAddress == null || !Permissions.bluetoothGranted(context)) return
        try {
            Core.init(context)
            // кулон ждали без записи — после перезагрузки снова ждём его через Bluetooth, без уведомления
            if (prefs.standby) {
                PendantWatch.start(context, prefs.deviceAddress!!)
                return
            }
            Core.startCapture(context)
        } catch (e: Exception) {
            Log.w("NeuroSoft", "auto start failed", e)
        }
    }
}
