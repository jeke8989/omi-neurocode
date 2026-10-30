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

    fun codecName(codec: Int?): String = when (codec) {
        null -> "—"
        CODEC_PCM16 -> "PCM 16 кГц"
        CODEC_PCM8 -> "PCM 8 кГц"
        CODEC_OPUS -> "Opus"
        CODEC_OPUS_FS320 -> "Opus 16 кГц / 20 мс"
        else -> "неизвестный ($codec)"
    }

    fun buttonName(code: Int): String = when (code) {
        BUTTON_SINGLE_TAP -> "одно нажатие — команда агенту"
        BUTTON_DOUBLE_TAP -> "двойное нажатие — «запомни это»"
        BUTTON_LONG_TAP -> "долгое нажатие"
        BUTTON_PRESS -> "нажата"
        BUTTON_RELEASE -> "отпущена"
        else -> "код $code"
    }
}
