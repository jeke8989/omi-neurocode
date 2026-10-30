package codes.neuro.neurosoft.diary

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import codes.neuro.neurosoft.net.Api
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Фото дня для дневника. Телефон отправляет уменьшенные кандидаты (1600 px), а сервер сам решает,
 * какие фото относятся к событиям дня: скриншоты, документы и случайные кадры в ленту не попадают.
 */
object PhotoSync {
    private const val TAG = "NeuroSoft.Photos"

    data class Prepared(val jpeg: ByteArray, val takenAt: Long?, val lat: Double?, val lon: Double?)

    /** Подготовить фото: уменьшить, повернуть по EXIF, достать дату и координаты. */
    fun prepare(context: Context, uri: Uri): Prepared? = try {
        val resolver = context.contentResolver
        var takenAt: Long? = null
        var lat: Double? = null
        var lon: Double? = null
        var rotation = 0
        resolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            rotation = exif.rotationDegrees
            exif.latLong?.let {
                lat = it[0]
                lon = it[1]
            }
            val dt = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            if (dt != null) {
                takenAt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(dt)?.time
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
        val bitmap = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IllegalStateException("cannot decode")
        val rotated = if (rotation != 0) {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true)
        } else bitmap
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 85, out)
        Prepared(out.toByteArray(), takenAt, lat, lon)
    } catch (e: Exception) {
        Log.w(TAG, "cannot prepare $uri", e)
        null
    }

    /** Загрузить выбранные вручную фото. Возвращает число отправленных. */
    suspend fun upload(context: Context, uris: List<Uri>, nearLocation: (Long) -> Pair<Double, Double>?): Int {
        var sent = 0
        for (uri in uris) {
            val p = prepare(context, uri) ?: continue
            val ts = p.takenAt ?: System.currentTimeMillis()
            val loc = if (p.lat != null && p.lon != null) p.lat to p.lon else nearLocation(ts)
            try {
                Api.uploadPhoto(p.jpeg, ts, loc?.first, loc?.second)
                sent++
            } catch (e: Exception) {
                Log.w(TAG, "upload failed", e)
            }
        }
        return sent
    }

    /** Новые снимки камеры за сегодня (без скриншотов), сделанные после последней синхронизации. */
    fun todaysCameraPhotos(context: Context, after: Long): List<Pair<Uri, Long>> {
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val since = maxOf(after, dayStart)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val pathColumn = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH
        else MediaStore.Images.Media.DATA
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, pathColumn)
        val out = mutableListOf<Pair<Uri, Long>>()
        try {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Images.Media.DATE_TAKEN} > ?", arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_TAKEN} ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val pathCol = c.getColumnIndexOrThrow(pathColumn)
                while (c.moveToNext() && out.size < 40) {
                    val path = c.getString(pathCol).orEmpty()
                    if (path.contains("Screenshot", ignoreCase = true)) continue
                    if (!path.contains("DCIM", ignoreCase = true)) continue // только камера
                    out += ContentUris.withAppendedId(collection, c.getLong(idCol)) to c.getLong(dateCol)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "no photo permission", e)
        }
        return out
    }
}
