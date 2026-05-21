package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.net.Uri
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.math.*

enum class VisualizationMode {
    CLASSIC_WAV_STEREO,
    OSCILLOSCOPE,
    CIRCULAR_RADAR,
    SPECTROGRAM,
    WATERFALL_3D,
    SPLIT_STEREO_SCOPE,
    HYBRID_WAV_SPECTRAL
}

object AudioEngine {
    private const val TAG = "AudioEngine"

    // Engine playback states
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentPlayheadMs = MutableStateFlow(0f)
    val currentPlayheadMs = _currentPlayheadMs.asStateFlow()

    private val _volume = MutableStateFlow(0.8f)
    val volume = _volume.asStateFlow()

    private val _speed = MutableStateFlow(1.0f)
    val speed = _speed.asStateFlow()

    private val _isLooping = MutableStateFlow(true)
    val isLooping = _isLooping.asStateFlow()

    private val _loopStartMs = MutableStateFlow(0f)
    val loopStartMs = _loopStartMs.asStateFlow()

    private val _loopEndMs = MutableStateFlow(12000f)
    val loopEndMs = _loopEndMs.asStateFlow()

    private val _fftSize = MutableStateFlow(1024)
    val fftSize = _fftSize.asStateFlow()

    private val _scaleType = MutableStateFlow("logarithmic") // "linear" or "logarithmic"
    val scaleType = _scaleType.asStateFlow()

    private val _visualizationMode = MutableStateFlow(VisualizationMode.CLASSIC_WAV_STEREO)
    val visualizationMode = _visualizationMode.asStateFlow()

    private val _playlist = MutableStateFlow<List<DecodedAudio>>(emptyList())
    val playlist = _playlist.asStateFlow()

    private val _activeTrack = MutableStateFlow<DecodedAudio?>(null)
    val activeTrack = _activeTrack.asStateFlow()

    private val _activeBpm = MutableStateFlow(120.0f)
    val activeBpm = _activeBpm.asStateFlow()

    private val _loadingProgress = MutableStateFlow<Float?>(null)
    val loadingProgress = _loadingProgress.asStateFlow()

    // Real-time analysis metrics
    private val _rmsDbL = MutableStateFlow(-120f)
    val rmsDbL = _rmsDbL.asStateFlow()

    private val _rmsDbR = MutableStateFlow(-120f)
    val rmsDbR = _rmsDbR.asStateFlow()

    private val _lufs = MutableStateFlow(-120f)
    val lufs = _lufs.asStateFlow()

    private val _peakFreq = MutableStateFlow(0f)
    val peakFreq = _peakFreq.asStateFlow()

    private val _stereoBalance = MutableStateFlow(0f) // -1.0 to 1.0
    val stereoBalance = _stereoBalance.asStateFlow()

    private val _clippingDetected = MutableStateFlow(false)
    val clippingDetected = _clippingDetected.asStateFlow()

    private val _isSilence = MutableStateFlow(true)
    val isSilence = _isSilence.asStateFlow()

    // Peak frequencies list for analyzer panel overlay
    private val _significantPeaks = MutableStateFlow<List<Pair<Float, Float>>>(emptyList())
    val significantPeaks = _significantPeaks.asStateFlow()

    // Real-time spectrum Buffers
    @Volatile
    var rtfReal = FloatArray(1024)
    @Volatile
    var rtfImag = FloatArray(1024)
    @Volatile
    var magnitudes = FloatArray(512)
    @Volatile
    var timeDomainSamples = FloatArray(1024) // scrolling oscilloscope samples

    // Full-track spectrogram cache: precalculated columns [timeSlice][frequencyBin]
    var spectrogramCache: Array<FloatArray>? = null
    var spectrogramProgress = MutableStateFlow(0f)

    // Selection indicators for navigation
    val selectionStartMs = MutableStateFlow<Float?>(null)
    val selectionEndMs = MutableStateFlow<Float?>(null)
    val markerMsList = MutableStateFlow<List<Float>>(emptyList())

    // Internal playback mechanism
    private var playerThread: Thread? = null
    private var audioTrack: AudioTrack? = null
    private var playheadSample = 0

    private val engineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    init {
        // Generate high-tech visual synthetic tracks off the main thread to avoid ANR/deadlocks
        engineScope.launch(Dispatchers.Default) {
            generateSyntheticAssets()
        }
    }

    private fun generateSyntheticAssets() {
        val tracks = listOf(
            generateSynthTechnoLoop(),
            generateSpaceAmbientPad(),
            generateLogSweep()
        )
        _playlist.value = tracks
        selectTrack(tracks[0])
    }

    fun selectTrack(track: DecodedAudio) {
        stop()
        _activeTrack.value = track
        playheadSample = 0
        _currentPlayheadMs.value = 0f
        _loopStartMs.value = 0f
        _loopEndMs.value = track.durationMs.toFloat()

        // Empty current selection bounds
        selectionStartMs.value = null
        selectionEndMs.value = null

        // Generate full-track Spectrogram map in the background (makes it super smooth/instant to browse)
        precalculateSpectrogram(track)

        // Asynchronously compute the BPM off the main thread
        engineScope.launch(Dispatchers.Default) {
            _activeBpm.value = BpmDetector.detectBpm(track.dataL, track.sampleRate)
        }
    }

    fun play() {
        if (_isPlaying.value) return
        val track = _activeTrack.value ?: return

        _isPlaying.value = true
        playerThread = Thread {
            playbackLoop(track)
        }.apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    fun pause() {
        _isPlaying.value = false
        val toJoin = playerThread
        playerThread = null
        engineScope.launch(Dispatchers.IO) {
            try {
                toJoin?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Error joining player thread: ${e.message}")
            }
        }
    }

    fun stop() {
        _isPlaying.value = false
        val toJoin = playerThread
        playerThread = null
        engineScope.launch(Dispatchers.IO) {
            try {
                toJoin?.join()
            } catch (e: Exception) {
                Log.e(TAG, "Error joining player thread: ${e.message}")
            }
            setPlayhead(0f)
        }
    }

    fun setPlayhead(positionMs: Float) {
        val track = _activeTrack.value ?: return
        val targetMs = positionMs.coerceIn(0f, track.durationMs.toFloat())
        playheadSample = ((targetMs / 1000f) * track.sampleRate).toInt()
        _currentPlayheadMs.value = targetMs
    }

    fun seekRelative(deltaMs: Float) {
        setPlayhead(_currentPlayheadMs.value + deltaMs)
    }

    fun setVolume(vol: Float) {
        _volume.value = vol.coerceIn(0f, 1f)
        try {
            audioTrack?.setVolume(_volume.value)
        } catch (ignored: Exception) {}
    }

    fun setSpeed(spd: Float) {
        _speed.value = spd.coerceIn(0.5f, 2.0f)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioTrack?.let { track ->
                    if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val params = PlaybackParams()
                        params.speed = _speed.value
                        track.playbackParams = params
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "AudioTrack speed adjustment failed: ${e.message}")
            }
        }
    }

    fun setLooping(loop: Boolean) {
        _isLooping.value = loop
    }

    fun setLoopRegion(startMs: Float, endMs: Float) {
        val track = _activeTrack.value ?: return
        val total = track.durationMs.toFloat()
        val s = startMs.coerceIn(0f, total)
        val e = endMs.coerceIn(s + 50f, total)
        _loopStartMs.value = s
        _loopEndMs.value = e
    }

    fun changeFftSize(size: Int) {
        if (size in listOf(256, 512, 1024, 2048)) {
            _fftSize.value = size
            rtfReal = FloatArray(size)
            rtfImag = FloatArray(size)
            magnitudes = FloatArray(size / 2)
            timeDomainSamples = FloatArray(size)
            
            // Recompute full spectrogram if track loaded
            _activeTrack.value?.let { precalculateSpectrogram(it) }
        }
    }

    fun setScaleType(scale: String) {
        _scaleType.value = scale
    }

    fun setVisualizationMode(mode: VisualizationMode) {
        _visualizationMode.value = mode
    }

    fun addMarkerAtCurrent() {
        val list = markerMsList.value.toMutableList()
        val curr = _currentPlayheadMs.value
        if (!list.contains(curr)) {
            list.add(curr)
            list.sort()
            markerMsList.value = list
        }
    }

    fun removeMarker(ms: Float) {
        val list = markerMsList.value.toMutableList()
        list.remove(ms)
        markerMsList.value = list
    }

    fun clearMarkers() {
        markerMsList.value = emptyList()
    }

    fun loadUserAudioUri(context: Context, uri: Uri) {
        engineScope.launch {
            _loadingProgress.value = 0.01f
            val decoded = AudioDecoder.decodeUri(context, uri) { progress ->
                _loadingProgress.value = progress
            }
            _loadingProgress.value = null
            if (decoded != null) {
                val currentList = _playlist.value.toMutableList()
                currentList.add(decoded)
                _playlist.value = currentList
                selectTrack(decoded)
            }
        }
    }

    private fun precalculateSpectrogram(track: DecodedAudio) {
        engineScope.launch(Dispatchers.Default) {
            spectrogramProgress.value = 0.05f
            val size = _fftSize.value
            val dataL = track.dataL
            val hopSize = size / 2
            val numWindows = (dataL.size - size) / hopSize

            if (numWindows <= 0) {
                spectrogramCache = null
                spectrogramProgress.value = 1f
                return@launch
            }

            val cache = Array(numWindows) { FloatArray(size / 2) }
            val real = FloatArray(size)
            val imag = FloatArray(size)
            val mags = FloatArray(size / 2)
            val hanningWin = FloatArray(size) { i ->
                0.5f * (1f - cos(2.0f * Math.PI * i / (size - 1))).toFloat()
            }

            for (w in 0 until numWindows) {
                if (w % 100 == 0) {
                    spectrogramProgress.value = 0.1f + (w.toFloat() / numWindows) * 0.9f
                    yield() // share computation thread
                }
                val offset = w * hopSize
                for (i in 0 until size) {
                    real[i] = dataL[offset + i] * hanningWin[i]
                    imag[i] = 0f
                }
                FFT.compute(real, imag)
                FFT.computeMagnitudes(real, imag, mags)
                System.arraycopy(mags, 0, cache[w], 0, size / 2)
            }
            spectrogramCache = cache
            spectrogramProgress.value = 1.0f
        }
    }

    // Main multi-threaded playback output engine
    private fun playbackLoop(track: DecodedAudio) {
        val sampleRate = track.sampleRate
        // Standard setup configurations
        val minBufSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = max(minBufSize, 4096 * 4)

        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        audioTrack?.setVolume(_volume.value)
        setSpeed(_speed.value)

        try {
            audioTrack?.play()
        } catch (e: Exception) {
            Log.e(TAG, "AudioTrack play startup crashed: ${e.message}")
            _isPlaying.value = false
            return
        }

        val trackDataL = track.dataL
        val trackDataR = track.dataR
        val sampleCount = trackDataL.size

        // Feed block chunks of sizes 512 frames (1024 shorts interleaved)
        val framesPerWrite = 512
        val samplesPerWrite = framesPerWrite * 2
        val shortBuffer = ShortArray(samplesPerWrite)

        // Hanning window for local analyser block
        val localFftSize = _fftSize.value
        val fftRealLocal = FloatArray(localFftSize)
        val fftImagLocal = FloatArray(localFftSize)
        val fftMagsLocal = FloatArray(localFftSize / 2)

        val windowFunc = FloatArray(localFftSize) { i ->
            0.5f * (1f - cos(2.0f * Math.PI * i / (localFftSize - 1))).toFloat()
        }

        while (_isPlaying.value) {
            val volFactor = _volume.value
            val isLoopingActive = _isLooping.value
            val loopStart = ((_loopStartMs.value / 1000f) * sampleRate).toInt().coerceIn(0, sampleCount - 1)
            val loopEnd = ((_loopEndMs.value / 1000f) * sampleRate).toInt().coerceIn(loopStart + 1000, sampleCount)

            // Loop Boundary evaluation
            if (isLoopingActive) {
                if (playheadSample < loopStart || playheadSample >= loopEnd) {
                    playheadSample = loopStart
                }
            } else {
                if (playheadSample >= sampleCount) {
                    _isPlaying.value = false
                    break
                }
            }

            val framesAvailable = if (isLoopingActive) loopEnd - playheadSample else sampleCount - playheadSample
            val framesToRead = min(framesPerWrite, framesAvailable)
            if (framesToRead <= 0) {
                if (isLoopingActive) {
                    playheadSample = loopStart
                    continue
                } else {
                    _isPlaying.value = false
                    break
                }
            }

            // Read samples and interleave for stereo playback
            for (f in 0 until framesToRead) {
                val idx = playheadSample + f
                val sL = trackDataL[idx] * volFactor
                val sR = trackDataR[idx] * volFactor

                shortBuffer[f * 2] = (sL * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
                shortBuffer[f * 2 + 1] = (sR * 32767f).coerceIn(-32768f, 32767f).toInt().toShort()
            }

            // Padding with silent channels if we hit end of file
            if (framesToRead < framesPerWrite) {
                for (i in (framesToRead * 2) until samplesPerWrite) {
                    shortBuffer[i] = 0
                }
            }

            // Write to AudioTrack block synchronously
            val written = audioTrack?.write(shortBuffer, 0, samplesPerWrite) ?: -1
            if (written < 0) {
                Log.e(TAG, "AudioTrack write failure: $written")
                _isPlaying.value = false
                break
            }

            // Update indices
            playheadSample += framesToRead
            val nextMs = (playheadSample.toFloat() / sampleRate) * 1000f
            _currentPlayheadMs.value = nextMs

            // Real-Time analyses: RMS Loudness, clipping detection
            var sumSqL = 0f
            var sumSqR = 0f
            var localClip = false
            var maxMagL = 0f

            for (f in 0 until framesToRead) {
                val idx = (playheadSample - framesToRead + f).coerceIn(0, sampleCount - 1)
                val sL = trackDataL[idx]
                val sR = trackDataR[idx]

                sumSqL += sL * sL
                sumSqR += sR * sR

                if (abs(sL) > 0.99f || abs(sR) > 0.99f) {
                    localClip = true
                }
                val absL = abs(sL)
                if (absL > maxMagL) {
                    maxMagL = absL
                }
            }

            _clippingDetected.value = localClip

            val divider = max(1, framesToRead)
            val rmsL = sqrt(sumSqL / divider)
            val rmsR = sqrt(sumSqR / divider)

            // Convert to dB
            val dbL = if (rmsL > 1e-5f) 20f * log10(rmsL) else -120f
            val dbR = if (rmsR > 1e-5f) 20f * log10(rmsR) else -120f

            _rmsDbL.value = dbL
            _rmsDbR.value = dbR
            _isSilence.value = (dbL < -60f && dbR < -60f)

            // Approximate LUFS: short-term integration
            val combRms = sqrt((rmsL * rmsL + rmsR * rmsR) / 2f)
            val estLufs = if (combRms > 1e-5f) (20f * log10(combRms) - 0.6f) else -120f
            _lufs.value = estLufs

            // Correlation coefficient (stereo alignment balance)
            var dotProduct = 0f
            for (f in 0 until framesToRead) {
                val idx = (playheadSample - framesToRead + f).coerceIn(0, sampleCount - 1)
                dotProduct += trackDataL[idx] * trackDataR[idx]
            }
            val denom = sqrt(sumSqL * sumSqR)
            val balance = if (denom > 1e-5f) dotProduct / denom else 0f
            _stereoBalance.value = balance.coerceIn(-1f, 1f)

            // Dynamic low latency real-time FFT
            val fftSizeNow = _fftSize.value
            val startIdx = (playheadSample - fftSizeNow).coerceAtLeast(0)

            // Copy samples to scrolling buffer
            val timeD = FloatArray(fftSizeNow)
            for (i in 0 until fftSizeNow) {
                val sampleIdx = (startIdx + i).coerceAtMost(sampleCount - 1)
                val samp = trackDataL[sampleIdx]
                timeD[i] = samp
                fftRealLocal[i] = samp * windowFunc[i]
                fftImagLocal[i] = 0f
            }
            timeDomainSamples = timeD

            FFT.compute(fftRealLocal, fftImagLocal)
            FFT.computeMagnitudes(fftRealLocal, fftImagLocal, fftMagsLocal)

            // Transfer to visual caches safely
            rtfReal = fftRealLocal.clone()
            rtfImag = fftImagLocal.clone()
            magnitudes = fftMagsLocal.clone()

            // Peak Frequency estimator
            var maxVal = -1f
            var maxBin = -1
            for (bin in 1 until fftMagsLocal.size) {
                if (fftMagsLocal[bin] > maxVal) {
                    maxVal = fftMagsLocal[bin]
                    maxBin = bin
                }
            }

            if (maxBin != -1) {
                val binFreq = (maxBin.toFloat() * sampleRate) / fftSizeNow
                _peakFreq.value = binFreq

                // Extract multiple peak harmonics for advanced overlay
                val peaks = mutableListOf<Pair<Float, Float>>()
                val threshold = maxVal * 0.15f
                for (bin in 2 until (fftMagsLocal.size - 2)) {
                    val prevVal2 = fftMagsLocal[bin - 2]
                    val prevVal = fftMagsLocal[bin - 1]
                    val curVal = fftMagsLocal[bin]
                    val nextVal = fftMagsLocal[bin + 1]
                    val nextVal2 = fftMagsLocal[bin + 2]

                    if (curVal > prevVal && curVal > nextVal && curVal > threshold) {
                        // Quadratic interpolation for accurate peaks
                        val p = 0.5f * (prevVal - nextVal) / (prevVal - 2f * curVal + nextVal)
                        val interpBin = bin + p
                        val freq = (interpBin * sampleRate) / fftSizeNow
                        peaks.add(Pair(freq, curVal))
                    }
                }
                peaks.sortByDescending { it.second }
                _significantPeaks.value = peaks.take(5)
            }
        }

        try {
            audioTrack?.stop()
            audioTrack?.release()
        } catch (ignored: Exception) {}
        audioTrack = null
        _isPlaying.value = false
    }

    // Synthesizer 1: Technical Tech-House drumbeat with resonant synthesizer sequences
    private fun generateSynthTechnoLoop(): DecodedAudio {
        val sr = 44100
        val seconds = 12
        val size = sr * seconds
        val dataL = FloatArray(size)
        val dataR = FloatArray(size)

        val beatInterval = (sr * 60f / 128f).toInt() // 128 BPM
        val noteInterval = beatInterval / 4

        for (i in 0 until size) {
            val beatPos = i % beatInterval
            val beatT = beatPos.toFloat() / sr

            // 1. Kick drum: rapid sweep downwards
            val kickF = max(32f, 160f * exp(-70.0 * beatT).toFloat())
            val kickA = max(0f, 1.0f - 11f * beatT).let { it * it }
            val kick = sin(2.0 * Math.PI * kickF * beatT).toFloat() * kickA

            // 2. Closed hi-hat on offbeat (interval / 2)
            val offbeatPos = (beatPos - beatInterval / 2)
            val offbeatT = if (offbeatPos > 0) offbeatPos.toFloat() / sr else 0f
            val hatA = if (offbeatPos > 0) max(0f, 0.42f - 75f * offbeatT).let { it * it } else 0f
            val hat = (Math.random().toFloat() * 2f - 1f) * hatA

            // 3. Acid synth line on 1/16th notes
            val stepIdx = (i / noteInterval) % 16
            val melScale = floatArrayOf(
                55.0f, 55.0f, 110.0f, 55.0f,
                65.4f, 55.0f, 98.0f, 110.0f,
                55.0f, 73.4f, 82.4f, 110.0f,
                55.0f, 110.0f, 65.4f, 82.4f
            )
            val noteF = melScale[stepIdx]
            val noteT = (i % noteInterval).toFloat() / sr
            val noteAmp = max(0f, 0.45f - 8.2f * noteT).let { it * it }
            
            // FM Synthesis modulator
            val mod = sin(2f * Math.PI * (noteF * 2.0f) * noteT).toFloat() * 3.8f
            val carrier = sin(2f * Math.PI * noteF * noteT + mod).toFloat() * noteAmp

            // Sum elements with master gains
            dataL[i] = (kick * 0.70f + hat * 0.35f + carrier * 0.45f).coerceIn(-1.0f, 1.0f)
            dataR[i] = (kick * 0.70f - hat * 0.35f + carrier * 0.40f).coerceIn(-1.0f, 1.0f)
        }

        return DecodedAudio(
            name = "Tech House Loop (128 BPM)",
            dataL = dataL,
            dataR = dataR,
            sampleRate = sr,
            channels = 2,
            durationMs = (seconds * 1000).toLong()
        )
    }

    // Synthesizer 2: Slow polyphonic warm soundscape with resonant LFO sweeping filter
    private fun generateSpaceAmbientPad(): DecodedAudio {
        val sr = 44100
        val seconds = 16
        val size = sr * seconds
        val dataL = FloatArray(size)
        val dataR = FloatArray(size)

        for (i in 0 until size) {
            val t = i.toFloat() / sr
            // LFOs for filters and dynamic movement
            val lfo1 = 0.5f + 0.35f * sin(2.0 * Math.PI * 0.08 * t).toFloat()
            val lfo2 = 0.5f + 0.30f * cos(2.0 * Math.PI * 0.14 * t).toFloat()

            // Root chord chord structures: A minor with dynamic notes: A2, C3, E3, G3
            val rootA = sin(2f * Math.PI * 110.0 * t) + 0.25f * sin(2f * Math.PI * 110.3 * t)
            val thirdC = sin(2f * Math.PI * 130.8 * t) + 0.25f * sin(2f * Math.PI * 130.5 * t)
            val fifthE = sin(2f * Math.PI * 164.8 * t) + 0.25f * sin(2f * Math.PI * 165.1 * t)
            val seventhG = sin(2f * Math.PI * 196.0 * t) + 0.25f * sin(2f * Math.PI * 195.7 * t)

            val mixed = (((rootA * 0.9 + thirdC * 0.8 + fifthE * 0.75 + seventhG * 0.5) / 4.0) * lfo1).toFloat()

            // Dynamic high filter ring oscillator sweep to visualize in waterfalls
            val ringFreq = 1100f + 850f * sin(2f * Math.PI * 0.06 * t).toFloat()
            val sweep = sin(2f * Math.PI * ringFreq * t).toFloat() * 0.09f * lfo2

            // High panning ping-pong sweep
            dataL[i] = (mixed * 0.75f + sweep).coerceIn(-1.0f, 1.0f)
            dataR[i] = (mixed * 0.65f - sweep).coerceIn(-1.0f, 1.0f)
        }

        return DecodedAudio(
            name = "Deep Space Pad (110 BPM)",
            dataL = dataL,
            dataR = dataR,
            sampleRate = sr,
            channels = 2,
            durationMs = (seconds * 1000).toLong()
        )
    }

    // Synthesizer 3: Ultra range sweep spanning audio extremes (20 Hz - 20000 Hz)
    private fun generateLogSweep(): DecodedAudio {
        val sr = 44100
        val seconds = 10
        val size = sr * seconds
        val dataL = FloatArray(size)
        val dataR = FloatArray(size)

        val fMin = 20.0
        val fMax = 20000.0
        val ratio = ln(fMax / fMin)

        for (i in 0 until size) {
            val t = i.toFloat() / sr
            val fraction = t / seconds

            // Phase equations resolving smooth sweeps
            val phase = 2f * Math.PI * fMin * seconds * (exp(fraction * ratio) - 1.0) / ratio
            val sweepL = sin(phase).toFloat() * 0.55f

            // Pan shifts phase by 90-degrees (quadrature balance)
            val phaseR = phase + (Math.PI / 2.0)
            val sweepR = sin(phaseR).toFloat() * 0.55f

            dataL[i] = sweepL
            dataR[i] = sweepR
        }

        return DecodedAudio(
            name = "Log Sine Sweep (20Hz-20kHz)",
            dataL = dataL,
            dataR = dataR,
            sampleRate = sr,
            channels = 2,
            durationMs = (seconds * 1000).toLong()
        )
    }
}
