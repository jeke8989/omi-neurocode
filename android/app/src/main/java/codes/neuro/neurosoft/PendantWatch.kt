package codes.neuro.neurosoft

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.app.NotificationCompat
import codes.neuro.neurosoft.ble.OmiProtocol

/**
 * Кулон долго не на связи (выключен, оставлен дома) — «ожидание»: служба записи и её постоянное уведомление
 * выключаются, а за появлением кулона следит сам Bluetooth телефона (фоновый поиск с фильтром, почти без
 * расхода батареи). Как только кулон снова рядом, Android будит приложение — запись включается сама.
 */
@SuppressLint("MissingPermission")
object PendantWatch {
    private const val TAG = "Mnemo.Watch"
    private const val CHANNEL = "pendant"

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 77, Intent(context, PendantSeenReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    private fun scanner(context: Context): BluetoothLeScanner? =
        context.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner

    /** Начать ждать кулон. false — Bluetooth выключен или нет разрешения: тогда ждём, пока откроют приложение. */
    fun start(context: Context, address: String): Boolean {
        val s = scanner(context) ?: return false
        val filters = listOf(
            ScanFilter.Builder().setDeviceAddress(address).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(OmiProtocol.AUDIO_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build()
        return try {
            s.startScan(filters, settings, pending(context)) == 0
        } catch (e: Exception) {
            Log.w(TAG, "cannot watch for the pendant", e)
            false
        }
    }

    fun stop(context: Context) {
        try {
            scanner(context)?.stopScan(pending(context))
        } catch (e: Exception) {
            Log.w(TAG, "cannot stop watching", e)
        }
    }

    /** Android не дал запустить запись из фона — тихо зовём владельца одним нажатием. */
    fun askToResume(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val r = Core.res()
        nm.createNotificationChannel(NotificationChannel(CHANNEL, r.getString(R.string.pendant_channel),
            NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 78, Intent(context, MainActivity::class.java).putExtra("open", "wake"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_neuro)
            .setContentTitle(r.getString(R.string.pendant_back_title))
            .setContentText(r.getString(R.string.pendant_back_text))
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            nm.notify(79, n)
        } catch (_: SecurityException) {
        }
    }
}

/** Bluetooth увидел кулон, пока приложение ждало его без записи. */
class PendantSeenReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Core.init(context)
        val address = Core.prefs.deviceAddress ?: return
        if (intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, 0) != 0) return
        @Suppress("DEPRECATION")
        val results: List<ScanResult> = (if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT, ScanResult::class.java)
        else intent.getParcelableArrayListExtra(BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT)) ?: return
        if (results.none { it.device?.address.equals(address, ignoreCase = true) }) return
        Core.wakeFromStandby(context, fromBackground = true)
    }
}
