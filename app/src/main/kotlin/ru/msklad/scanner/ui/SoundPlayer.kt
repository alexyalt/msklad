package ru.msklad.scanner.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import ru.msklad.scanner.R

class SoundPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val successId = pool.load(context, R.raw.success, 1)
    private val errorId = pool.load(context, R.raw.error, 1)

    fun success() { pool.play(successId, 1f, 1f, 1, 0, 1f) }

    fun error() { pool.play(errorId, 1f, 1f, 1, 0, 1f) }

    fun release() = pool.release()
}
