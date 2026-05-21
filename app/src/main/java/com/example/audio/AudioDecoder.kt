package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaExtractor
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AudioDecoder {
    private const val TAG = "AudioDecoder"

    suspend fun decodeUri(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit
    ): DecodedAudio? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { fileDesc ->
                extractor.setDataSource(fileDesc.fileDescriptor)
            } ?: return@withContext null

            // Find the audio track index
            var trackIndex = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    format = fmt
                    break
                }
            }

            if (trackIndex == -1 || format == null) {
                Log.e(TAG, "No valid audio track found in media file.")
                return@withContext null
            }

            extractor.selectTrack(trackIndex)

            val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
            val sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            val durationMs = durationUs / 1000

            Log.d(TAG, "Decoding audio: Mime=$mime, SampleRate=$sampleRate, Chan=$channels, DurUs=$durationUs")

            val activeCodec = MediaCodec.createDecoderByType(mime)
            codec = activeCodec
            activeCodec.configure(format, null, null, 0)
            activeCodec.start()

            val info = MediaCodec.BufferInfo()
            var isExtractorEOS = false
            var isDecoderEOS = false

            // Collect buffers of byte chunks
            val byteChunks = mutableListOf<ByteArray>()
            var totalBytesDecoded = 0

            // Max sample bounds to prevent OutOfMemory during massive files (e.g., limit to 5 mins of stereo 16-bit 44.1kHz)
            val maxBytesToDecode = 5 * 60 * sampleRate * channels * 2

            onProgress(0.01f)

            while (!isDecoderEOS && totalBytesDecoded < maxBytesToDecode) {
                if (!isExtractorEOS) {
                    val inIdx = activeCodec.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inputBuffer = activeCodec.getInputBuffer(inIdx)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                activeCodec.queueInputBuffer(
                                    inIdx, 0, 0, 0L,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                isExtractorEOS = true
                            } else {
                                activeCodec.queueInputBuffer(
                                    inIdx, 0, sampleSize,
                                    extractor.sampleTime, 0
                                )
                                extractor.advance()
                            }
                        }
                    }
                }

                val outIdx = activeCodec.dequeueOutputBuffer(info, 10000)
                if (outIdx >= 0) {
                    val outputBuffer = activeCodec.getOutputBuffer(outIdx)
                    if (outputBuffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outputBuffer.position(info.offset)
                        outputBuffer.get(chunk)
                        byteChunks.add(chunk)
                        totalBytesDecoded += info.size

                        val progress = if (durationUs > 0) {
                            (info.presentationTimeUs.toFloat() / durationUs).coerceIn(0f, 1f)
                        } else {
                            0.5f
                        }
                        onProgress(0.05f + progress * 0.90f)
                    }
                    activeCodec.releaseOutputBuffer(outIdx, false)

                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // Ignoured format shifts
                }
            }

            // Convert interleaved raw 16-bit byte samples to amplitude arrays
            val totalSamples = totalBytesDecoded / 2
            val fullPcm = ShortArray(totalSamples)
            var pcmIndex = 0
            for (chunk in byteChunks) {
                var pos = 0
                while (pos < chunk.size && pcmIndex < totalSamples) {
                    val low = chunk[pos].toInt() and 0xFF
                    val high = chunk[pos + 1].toInt()
                    val sample = ((high shl 8) or low).toShort()
                    fullPcm[pcmIndex++] = sample
                    pos += 2
                }
            }

            val channelSamples = totalSamples / channels
            val dataL = FloatArray(channelSamples)
            val dataR = if (channels == 2) FloatArray(channelSamples) else FloatArray(0)

            if (channels == 2) {
                for (i in 0 until channelSamples) {
                    dataL[i] = fullPcm[i * 2] / 32768.1f
                    dataR[i] = fullPcm[i * 2 + 1] / 32768.1f
                }
            } else {
                for (i in 0 until channelSamples) {
                    val valL = fullPcm[i] / 32768.1f
                    dataL[i] = valL
                }
            }

            val finalFileName = getFileName(context, uri)
            onProgress(1.0f)

            DecodedAudio(
                name = finalFileName,
                dataL = dataL,
                dataR = if (channels == 2) dataR else dataL,
                sampleRate = sampleRate,
                channels = channels,
                durationMs = durationMs
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception decoding layout content: ${e.message}", e)
            null
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (ignored: Exception) {}
            try {
                extractor.release()
            } catch (ignored: Exception) {}
        }
    }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = "External Track"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error looking up filename: ${e.message}")
        }
        return name
    }
}

data class DecodedAudio(
    val name: String,
    val dataL: FloatArray,
    val dataR: FloatArray,
    val sampleRate: Int,
    val channels: Int,
    val durationMs: Long
)
