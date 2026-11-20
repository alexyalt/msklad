package ru.msklad.scanner.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.util.Log
import ru.msklad.scanner.R

class SoundPlayer(context: Context) {
    // USAGE_MEDIA: звук идёт по громкости мультимедиа, а не уведомлений, которую часто выключают.
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = mapOf(
        Sound.SCAN to pool.load(context, R.raw.scan, 1),
        Sound.SUCCESS to pool.load(context, R.raw.success, 1),
        Sound.ERROR to pool.load(context, R.raw.error, 1),
    )

    fun play(sound: Sound) {
        val stream = pool.play(ids.getValue(sound), 1f, 1f, 1, 0, 1f)
        if (stream == 0) Log.w(TAG, "Звук $sound не воспроизведён: файл ещё не загружен")
    }

    fun release() = pool.release()

    private companion object {
        const val TAG = "SoundPlayer"
    }
}
