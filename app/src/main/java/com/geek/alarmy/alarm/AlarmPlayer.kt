package com.geek.alarmy.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

data class RingSpec(
    val ringtoneUri: String?,
    /** Alarm stream volume, percent. */
    val volume: Int,
    val gradualSeconds: Int,
    val vibrate: Boolean,
)

/**
 * Plays the alarm sound with every fallback we can think of, because silence is the one outcome
 * that must never happen:
 *
 *  1. the alarm's chosen sound,
 *  2. the system default alarm sound,
 *  3. the system default ringtone,
 *  4. a tone synthesized in memory (needs no file, works before first unlock).
 *
 * A watchdog restarts playback (moving down the list) if a player errors out, stalls while
 * preparing, or silently stops.
 */
class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var spec: RingSpec? = null
    private var sources = ArrayDeque<Uri?>()
    private var media: MediaPlayer? = null
    private var mediaPrepared = false
    private var tone: AudioTrack? = null
    private var gain = 1f
    private var rampStartedAt = 0L
    private var jobs = mutableListOf<Job>()
    private var savedVolume: Int? = null
    private var focus: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    val isRinging: Boolean get() = spec != null

    fun start(spec: RingSpec) {
        stop()
        this.spec = spec
        setStreamVolume(spec.volume)
        requestFocus()
        sources = ArrayDeque(buildSources(spec.ringtoneUri))
        rampStartedAt = System.currentTimeMillis()
        gain = gainAt(0L)
        playNext()
        if (spec.vibrate) startVibration()
        jobs += scope.launch { rampLoop(spec) }
        jobs += scope.launch { watchdogLoop() }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
        releaseMedia()
        releaseTone()
        vibrator?.cancel()
        vibrator = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        restoreStreamVolume()
        spec = null
    }

    fun release() {
        stop()
        scope.cancel()
    }

    private fun buildSources(chosen: String?): List<Uri?> {
        val list = LinkedHashSet<Uri?>()
        chosen?.let { runCatching { it.toUri() }.getOrNull() }?.let(list::add)
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_ALARM) }
            .getOrNull()?.let(list::add)
        list.add(Settings.System.DEFAULT_ALARM_ALERT_URI)
        runCatching { RingtoneManager.getActualDefaultRingtoneUri(context, RingtoneManager.TYPE_RINGTONE) }
            .getOrNull()?.let(list::add)
        list.add(null) // synthesized tone
        return list.toList()
    }

    private fun playNext() {
        releaseMedia()
        releaseTone()
        while (sources.isNotEmpty()) {
            val uri = sources.removeFirst()
            if (uri == null) {
                if (playTone()) return
            } else if (playMedia(uri)) {
                return
            }
        }
        // Everything failed, including the synthesized tone. Try the tone once more.
        Log.e(TAG, "All sound sources failed; retrying synthesized tone")
        sources.add(null)
    }

    private fun playMedia(uri: Uri): Boolean = try {
        Log.i(TAG, "Playing $uri")
        mediaPrepared = false
        media = MediaPlayer().apply {
            setAudioAttributes(attributes)
            setDataSource(context, uri)
            isLooping = true
            setVolume(gain, gain)
            setOnErrorListener { _, what, extra ->
                Log.w(TAG, "MediaPlayer error $what/$extra on $uri")
                playNext()
                true
            }
            setOnPreparedListener {
                mediaPrepared = true
                it.setVolume(gain, gain)
                it.start()
            }
            prepareAsync()
        }
        true
    } catch (e: Exception) {
        Log.w(TAG, "Can't play $uri", e)
        releaseMedia()
        false
    }

    private fun playTone(): Boolean = try {
        Log.i(TAG, "Playing synthesized tone")
        val pcm = synthesizeBeeps()
        tone = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STATIC)
            .setBufferSizeInBytes(pcm.size * 2)
            .build()
            .apply {
                write(pcm, 0, pcm.size)
                setLoopPoints(0, pcm.size, -1)
                setVolume(gain)
                play()
            }
        true
    } catch (e: Exception) {
        Log.e(TAG, "Synthesized tone failed", e)
        releaseTone()
        false
    }

    /** Two short 880 Hz beeps then a pause, as 16-bit mono PCM. */
    private fun synthesizeBeeps(): ShortArray {
        val beep = SAMPLE_RATE * 18 / 100
        val gap = SAMPLE_RATE * 12 / 100
        val pause = SAMPLE_RATE * 55 / 100
        val out = ShortArray(beep * 2 + gap + pause)
        fun writeBeep(offset: Int) {
            for (i in 0 until beep) {
                val env = minOf(1.0, i / 200.0, (beep - i) / 200.0) // avoid clicks
                out[offset + i] = (sin(2 * PI * 880 * i / SAMPLE_RATE) * env * Short.MAX_VALUE * 0.9).roundToInt().toShort()
            }
        }
        writeBeep(0)
        writeBeep(beep + gap)
        return out
    }

    private suspend fun rampLoop(spec: RingSpec) {
        if (spec.gradualSeconds <= 0) {
            applyGain(1f)
            return
        }
        while (scope.isActive) {
            val elapsed = System.currentTimeMillis() - rampStartedAt
            applyGain(gainAt(elapsed))
            if (elapsed >= spec.gradualSeconds * 1000L) return
            delay(200)
        }
    }

    /** Ramps linearly in decibels (i.e. exponentially in amplitude), which sounds even to the ear. */
    private fun gainAt(elapsedMs: Long): Float {
        val total = (spec?.gradualSeconds ?: 0) * 1000L
        if (total <= 0) return 1f
        val p = (elapsedMs.toFloat() / total).coerceIn(0f, 1f)
        return MIN_GAIN * (1f / MIN_GAIN).pow(p)
    }

    private fun applyGain(g: Float) {
        gain = g
        runCatching { media?.setVolume(g, g) }
        runCatching { tone?.setVolume(g) }
    }

    private suspend fun watchdogLoop() {
        var preparingSince = System.currentTimeMillis()
        var lastMedia: MediaPlayer? = null
        while (scope.isActive) {
            delay(WATCHDOG_MS)
            val m = media
            val t = tone
            if (m !== lastMedia) {
                lastMedia = m
                preparingSince = System.currentTimeMillis()
            }
            val healthy = when {
                m != null && !mediaPrepared -> System.currentTimeMillis() - preparingSince < PREPARE_TIMEOUT_MS
                m != null -> runCatching { m.isPlaying }.getOrDefault(false)
                t != null -> t.playState == AudioTrack.PLAYSTATE_PLAYING
                else -> false
            }
            if (!healthy) {
                Log.w(TAG, "Watchdog: playback not healthy, trying next source")
                playNext()
            }
            // Someone (e.g. a volume key or another app) may have muted the alarm stream.
            spec?.let { if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) setStreamVolume(it.volume, save = false) }
        }
    }

    private fun setStreamVolume(percent: Int, save: Boolean = true) {
        try {
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (save && savedVolume == null) savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            val target = (max * percent.coerceIn(1, 100) / 100f).roundToInt().coerceIn(1, max)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Can't set alarm volume", e)
        }
    }

    private fun restoreStreamVolume() {
        val v = savedVolume ?: return
        savedVolume = null
        runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, v, 0) }
    }

    private fun requestFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { }
            .build()
        // Play regardless of the result; an alarm doesn't yield to other audio.
        runCatching { audio.requestAudioFocus(req) }
        focus = req
    }

    private fun startVibration() {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 800, 600), 0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(effect, attributes)
            }
            vibrator = v
        } catch (e: Exception) {
            Log.w(TAG, "Vibration failed", e)
        }
    }

    private fun releaseMedia() {
        media?.let { runCatching { it.stop() }; runCatching { it.release() } }
        media = null
        mediaPrepared = false
    }

    private fun releaseTone() {
        tone?.let { runCatching { it.stop() }; runCatching { it.release() } }
        tone = null
    }

    companion object {
        private const val TAG = "AlarmPlayer"
        private const val SAMPLE_RATE = 44_100
        private const val MIN_GAIN = 0.02f // about -34 dB
        private const val WATCHDOG_MS = 2_000L
        private const val PREPARE_TIMEOUT_MS = 5_000L
    }
}
