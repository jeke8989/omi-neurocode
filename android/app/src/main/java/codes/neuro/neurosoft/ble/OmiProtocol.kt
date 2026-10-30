package codes.neuro.neurosoft.ble

import java.util.UUID

/**
 * Bluetooth-протокол кулона Omi (прошивка BasedHardware/omi, сверено с transport.c и button.c, CV1 3.0.x).
 */
object OmiProtocol {
    val AUDIO_SERVICE: UUID = UUID.fromString("19b10000-e8f2-537e-4f6c-d104768a1214")
    val AUDIO_DATA: UUID = UUID.fromString("19b10001-e8f2-537e-4f6c-d104768a1214")
    val AUDIO_CODEC: UUID = UUID.fromString("19b10002-e8f2-537e-4f6c-d104768a1214")

    val BUTTON_SERVICE: UUID = UUID.fromString("23ba7924-0000-1000-7450-346eac492e92")
    val BUTTON_TRIGGER: UUID = UUID.fromString("23ba7925-0000-1000-7450-346eac492e92")

    val TIME_SYNC_SERVICE: UUID = UUID.fromString("19b10030-e8f2-537e-4f6c-d104768a1214")
    val TIME_SYNC_WRITE: UUID = UUID.fromString("19b10031-e8f2-537e-4f6c-d104768a1214")

    val BATTERY_SERVICE: UUID = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb")
    val BATTERY_LEVEL: UUID = UUID.fromString("00002a19-0000-1000-8000-00805f9b34fb")

    val DEVICE_INFO_SERVICE: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val FIRMWARE_REVISION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
    val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
    val HARDWARE_REVISION: UUID = UUID.fromString("00002a27-0000-1000-8000-00805f9b34fb")
    val MANUFACTURER: UUID = UUID.fromString("00002a29-0000-1000-8000-00805f9b34fb")

    // Настройки кулона (transport.c): яркость LED 0–100 %, усиление микрофона 0–8, зарядка 0/1
    val SETTINGS_SERVICE: UUID = UUID.fromString("19b10010-e8f2-537e-4f6c-d104768a1214")
    val LED_DIM: UUID = UUID.fromString("19b10011-e8f2-537e-4f6c-d104768a1214")
    val MIC_GAIN: UUID = UUID.fromString("19b10012-e8f2-537e-4f6c-d104768a1214")
    val CHARGING: UUID = UUID.fromString("19b10013-e8f2-537e-4f6c-d104768a1214")
    const val MIC_GAIN_MAX = 8

    // Вибромотор CV1: 1 — 100 мс, 2 — 300 мс, 3 — 500 мс
    val HAPTIC_SERVICE: UUID = UUID.fromString("cab1ab95-2ea5-4f4d-bb56-874b72cfc984")
    val HAPTIC: UUID = UUID.fromString("cab1ab96-2ea5-4f4d-bb56-874b72cfc984")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Кодеки (характеристика AUDIO_CODEC). CV1 на прошивке 3.x отдаёт 21: Opus, 16 кГц, кадр 20 мс.
    const val CODEC_PCM16 = 0
    const val CODEC_PCM8 = 1
    const val CODEC_OPUS = 20
    const val CODEC_OPUS_FS320 = 21

    // События кнопки (первый байт уведомления). Долгое нажатие выключает кулон и не отправляется.
    const val BUTTON_SINGLE_TAP = 1
    const val BUTTON_DOUBLE_TAP = 2
    const val BUTTON_LONG_TAP = 3
    const val BUTTON_PRESS = 4
    const val BUTTON_RELEASE = 5

    /** Техническое название кодека (одинаково на всех языках). */
    fun codecName(codec: Int?): String = when (codec) {
        null -> "—"
        CODEC_PCM16 -> "PCM, 16 kHz"
        CODEC_PCM8 -> "PCM, 8 kHz"
        CODEC_OPUS -> "Opus"
        CODEC_OPUS_FS320 -> "Opus, 16 kHz, 20 ms"
        else -> "#$codec"
    }
}
