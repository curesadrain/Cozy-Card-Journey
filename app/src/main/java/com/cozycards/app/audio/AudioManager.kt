package com.cozycards.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import com.cozycards.app.R
import com.cozycards.app.data.AppSettings

enum class Sfx(val res: Int) {
    MATCH(R.raw.sfx_match),
    CASCADE(R.raw.sfx_cascade),
    SPECIAL(R.raw.sfx_special),
    ROCKET(R.raw.sfx_rocket),
    BOMB(R.raw.sfx_bomb),
    RAINBOW(R.raw.sfx_rainbow),
    BOOSTER(R.raw.sfx_booster),
    BOX_OPEN(R.raw.sfx_box_open),
    RARE_CARD(R.raw.sfx_rare),
    WIN(R.raw.sfx_win),
    LOSE(R.raw.sfx_lose),
    SWISH(R.raw.sfx_swish),
    TAP(R.raw.sfx_tap),
    COIN(R.raw.sfx_coin),
    INVALID(R.raw.sfx_invalid),
}

/**
 * Sound effects via SoundPool (low latency, preloaded) and looping background music via
 * MediaPlayer. Music pauses with the app lifecycle. All audio is original and synthesised
 * by tools/synth_audio.py.
 */
class GameAudio(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(6)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        .build()
    private val ids = HashMap<Sfx, Int>()
    private val loaded = HashSet<Int>()
    private var music: MediaPlayer? = null
    private var settings = AppSettings()
    private var foreground = false
    private val lastPlayed = HashMap<Sfx, Long>()

    init {
        pool.setOnLoadCompleteListener { _, id, status -> if (status == 0) synchronized(loaded) { loaded += id } }
        Sfx.entries.forEach { ids[it] = pool.load(context, it.res, 1) }
    }

    @Synchronized
    fun apply(s: AppSettings) {
        settings = s
        updateMusic()
    }

    fun play(sfx: Sfx, rate: Float = 1f) {
        if (settings.muted || settings.sfxVolume <= 0f) return
        val now = System.currentTimeMillis()
        // Avoid stacking the same sound within 40 ms (big cascades).
        if (now - (lastPlayed[sfx] ?: 0) < 40) return
        lastPlayed[sfx] = now
        val id = ids[sfx] ?: return
        if (synchronized(loaded) { id !in loaded }) return
        val v = settings.sfxVolume
        pool.play(id, v, v, 1, 0, rate.coerceIn(0.5f, 2f))
    }

    @Synchronized
    fun onForeground(fg: Boolean) {
        foreground = fg
        updateMusic()
    }

    private fun updateMusic() {
        val shouldPlay = foreground && !settings.muted && settings.musicVolume > 0f
        if (shouldPlay) {
            val mp = music ?: MediaPlayer.create(context, R.raw.music_journey)?.also {
                it.isLooping = true
                music = it
            } ?: return
            mp.setVolume(settings.musicVolume * 0.7f, settings.musicVolume * 0.7f)
            if (!mp.isPlaying) mp.start()
        } else {
            music?.let { if (it.isPlaying) it.pause() }
        }
    }

    @Synchronized
    fun release() {
        music?.release(); music = null
        pool.release()
    }
}
