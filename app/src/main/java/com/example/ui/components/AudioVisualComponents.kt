package com.example.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.audio.*
import com.example.ui.theme.*
import kotlin.math.*

/**
 * High-Resolution Stereo Waveform Visualizer with Scroll & Zoom.
 * Also includes a Minimap Overview at the bottom for easy timeline navigation.
 */
@Composable
fun StereoWaveformWithMinimap(
    track: DecodedAudio,
    currentPlayheadMs: Float,
    loopStartMs: Float,
    loopEndMs: Float,
    isLooping: Boolean,
    onSeek: (Float) -> Unit,
    onSetLoop: (Float, Float) -> Unit,
    selectionStartMs: Float?,
    selectionEndMs: Float?,
    onSetSelection: (Float?, Float?) -> Unit,
    markers: List<Float>,
    onAddMarker: (Float) -> Unit,
    onRemoveMarker: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoom by remember { mutableStateOf(1.0f) } // 1.0x to 50.0x
    var scrollOffset by remember { mutableStateOf(0.0f) } // 0.0 to 1.0 (scroll fraction)
    var waveformHeightMultiplier by remember { mutableStateOf(1.0f) }

    // Precalculate a fast downsampled overview (150 peaks) for the minimap to avoid doing it per-frame
    val minimapPeaks = remember(track) {
        val step = max(1, track.dataL.size / 200)
        FloatArray(200) { i ->
            val start = i * step
            var maxVal = 0f
            for (offset in 0 until step) {
                val idx = start + offset
                if (idx < track.dataL.size) {
                    val valL = abs(track.dataL[idx])
                    val valR = if (idx < track.dataR.size) abs(track.dataR[idx]) else 0f
                    val combined = (valL + valR) / 2.1f
                    if (combined > maxVal) maxVal = combined
                }
            }
            maxVal.coerceIn(0f, 1f)
        }
    }

    val durationMs = track.durationMs.toFloat()

    // Determine sample range visible on screen based on zoom and scrollOffset
    val visibleDuration = durationMs / zoom
    val maxScrollOffset = max(0f, durationMs - visibleDuration)
    val startVisibleMs = scrollOffset * maxScrollOffset
    val endVisibleMs = startVisibleMs + visibleDuration

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        // Timeline Header: zoom slider, height slider, selection diagnostics
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "STRETCHED VIEWPORT: ${startVisibleMs.toInt()} ms - ${endVisibleMs.toInt()} ms",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                if (selectionStartMs != null && selectionEndMs != null) {
                    Text(
                        text = "REGION SELECTED: ${selectionStartMs.toInt()} to ${selectionEndMs.toInt()} ms (Δ ${(selectionEndMs - selectionStartMs).toInt()} ms)",
                        color = VividOrange,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                } else {
                    Text(
                        text = "CLICK & DRAG TO SELECT ANALYTICAL REGION",
                        color = TextMuted,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Zoom", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                Slider(
                    value = zoom,
                    onValueChange = { zoom = it },
                    valueRange = 1f..32f,
                    modifier = Modifier.width(90.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = CyberCyan,
                        activeTrackColor = CyberCyan
                    )
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text("H-Gain", color = TextMuted, fontSize = 11.sp, modifier = Modifier.padding(end = 4.dp))
                Slider(
                    value = waveformHeightMultiplier,
                    onValueChange = { waveformHeightMultiplier = it },
                    valueRange = 0.5f..2.5f,
                    modifier = Modifier.width(80.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = CyberGreen,
                        activeTrackColor = CyberGreen
                    )
                )
            }
        }

        // Active Waveform Rendering view
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkObsidian)
                .pointerInput(track) {
                    // Double tap adds marker. Tap seeks. Drag selects a region.
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val clickedFrac = offset.x / size.width
                            val clickedMs = startVisibleMs + (clickedFrac * (endVisibleMs - startVisibleMs))
                            onAddMarker(clickedMs)
                        },
                        onTap = { offset ->
                            val clickedFrac = offset.x / size.width
                            val clickedMs = startVisibleMs + (clickedFrac * (endVisibleMs - startVisibleMs))
                            onSeek(clickedMs)
                        }
                    )
                }
                .pointerInput(track) {
                    // Clicking and dragging selects a range (selection)
                    var dragStartMs = 0f
                    detectDragGestures(
                        onDragStart = { offset ->
                            val clickedFrac = offset.x / size.width
                            dragStartMs = startVisibleMs + (clickedFrac * (endVisibleMs - startVisibleMs))
                            onSetSelection(dragStartMs, dragStartMs)
                        },
                        onDrag = { change, _ ->
                            val currentFrac = change.position.x / size.width
                            val currentMs = startVisibleMs + (currentFrac * (endVisibleMs - startVisibleMs))
                            val start = minOf(dragStartMs, currentMs).coerceIn(0f, durationMs)
                            val end = maxOf(dragStartMs, currentMs).coerceIn(0f, durationMs)
                            onSetSelection(start, end)
                        },
                        onDragEnd = {
                            // If selection is too tiny, wipe it out
                            if (selectionStartMs != null && selectionEndMs != null && abs(selectionEndMs - selectionStartMs) < 30f) {
                                onSetSelection(null, null)
                            }
                        }
                    )
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val midY = height / 2f
                val chHeight = height / 2f // split upper for Left, lower for Right

                // Drawing secondary lines (DB lines / amplitude ruler)
                val dbLadders = floatArrayOf(0.1f, 0.25f, 0.5f, 0.75f)
                dbLadders.forEach { amp ->
                    val lineYL_upper = (chHeight / 2f) - (chHeight / 2f) * amp
                    val lineYL_lower = (chHeight / 2f) + (chHeight / 2f) * amp
                    val lineYR_upper = chHeight + (chHeight / 2f) - (chHeight / 2f) * amp
                    val lineYR_lower = chHeight + (chHeight / 2f) + (chHeight / 2f) * amp

                    // Horisontal lines
                    drawLine(GridLineColor, Offset(0f, lineYL_upper), Offset(width, lineYL_upper), 1f)
                    drawLine(GridLineColor, Offset(0f, lineYL_lower), Offset(width, lineYL_lower), 1f)
                    drawLine(GridLineColor, Offset(0f, lineYR_upper), Offset(width, lineYR_upper), 1f)
                    drawLine(GridLineColor, Offset(0f, lineYR_lower), Offset(width, lineYR_lower), 1f)
                }

                // Grid divisions (every 0.5 sec or similar depending on zoom)
                val gridSecs = if (visibleDuration > 10000) 2.0f else if (visibleDuration > 2000) 0.5f else 0.1f
                var secOffset = ceil(startVisibleMs / 1000f / gridSecs) * gridSecs
                while (secOffset * 1000f < endVisibleMs) {
                    val frac = (secOffset * 1000f - startVisibleMs) / (endVisibleMs - startVisibleMs)
                    val x = frac * width
                    drawLine(GridLineColor, Offset(x, 0f), Offset(x, height), 1f)
                    secOffset += gridSecs
                }

                // Split Stereophonic Divider Line
                drawLine(GridLineColor, Offset(0f, chHeight), Offset(width, chHeight), 2f)

                // 2. Waveform calculation peaks
                val startSampleIdx = ((startVisibleMs / 1000f) * track.sampleRate).toInt().coerceIn(0, track.dataL.size - 1)
                val endSampleIdx = ((endVisibleMs / 1000f) * track.sampleRate).toInt().coerceIn(0, track.dataL.size)
                val visibleSamples = endSampleIdx - startSampleIdx

                if (visibleSamples > 5) {
                    val samplesPerPixel = visibleSamples / width.toInt().coerceAtLeast(1)

                    // Waveform gradient brushes
                    val brushLeft = Brush.verticalGradient(
                        colors = listOf(CyberCyan, CyberGreen),
                        startY = 0f,
                        endY = chHeight
                    )
                    val brushRight = Brush.verticalGradient(
                        colors = listOf(CyberGreen, VividOrange),
                        startY = chHeight,
                        endY = height
                    )

                    for (x in 0 until width.toInt()) {
                        val pixelSampleStart = startSampleIdx + (x * samplesPerPixel)
                        if (pixelSampleStart >= track.dataL.size) break

                        var maxL = 0f
                        var minL = 0f
                        var maxR = 0f
                        var minR = 0f

                        val lookAhead = max(1, samplesPerPixel)
                        for (offset in 0 until lookAhead) {
                            val sampIdx = pixelSampleStart + offset
                            if (sampIdx < track.dataL.size) {
                                val sL = track.dataL[sampIdx] * waveformHeightMultiplier
                                if (sL > maxL) maxL = sL
                                if (sL < minL) minL = sL

                                val sR = if (sampIdx < track.dataR.size) track.dataR[sampIdx] * waveformHeightMultiplier else 0f
                                if (sR > maxR) maxR = sR
                                if (sR < minR) minR = sR
                            }
                        }

                        // Coordinates for Left Channel (Upper half of Canvas)
                        val leftCenterY = chHeight / 2f
                        val topY_L = leftCenterY - (maxL * leftCenterY).coerceAtMost(leftCenterY)
                        val bottomY_L = leftCenterY - (minL * leftCenterY).coerceAtLeast(-leftCenterY)

                        drawLine(
                            brush = brushLeft,
                            start = Offset(x.toFloat(), topY_L),
                            end = Offset(x.toFloat(), bottomY_L),
                            strokeWidth = 1.5f
                        )

                        // Coordinates for Right Channel (Lower half of Canvas)
                        val rightCenterY = chHeight + chHeight / 2f
                        val topY_R = rightCenterY - (maxR * (chHeight / 2f)).coerceAtMost(chHeight / 2f)
                        val bottomY_R = rightCenterY - (minR * (chHeight / 2f)).coerceAtLeast(-chHeight / 2f)

                        drawLine(
                            brush = brushRight,
                            start = Offset(x.toFloat(), topY_R),
                            end = Offset(x.toFloat(), bottomY_R),
                            strokeWidth = 1.5f
                        )
                    }
                }

                // 3. Highlight selection region if set
                if (selectionStartMs != null && selectionEndMs != null) {
                    val sFrac = (selectionStartMs - startVisibleMs) / (endVisibleMs - startVisibleMs)
                    val eFrac = (selectionEndMs - startVisibleMs) / (endVisibleMs - startVisibleMs)
                    val sX = sFrac * width
                    val eX = eFrac * width
                    drawRect(
                        color = VividOrange.copy(alpha = 0.18f),
                        topLeft = Offset(sX, 0f),
                        size = Size(eX - sX, height)
                    )
                    // Draw selection border keys
                    drawLine(VividOrange, Offset(sX, 0f), Offset(sX, height), 1.5f)
                    drawLine(VividOrange, Offset(eX, 0f), Offset(eX, height), 1.5f)
                }

                // 4. Draw loop markers (Magenta colors)
                if (isLooping) {
                    val lStartFrac = (loopStartMs - startVisibleMs) / (endVisibleMs - startVisibleMs)
                    val lEndFrac = (loopEndMs - startVisibleMs) / (endVisibleMs - startVisibleMs)

                    if (lStartFrac in 0f..1f) {
                        val lx = lStartFrac * width
                        drawLine(NeonPink, Offset(lx, 0f), Offset(lx, height), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)))
                        drawCircle(NeonPink, 6f, Offset(lx, 10f))
                    }
                    if (lEndFrac in 0f..1f) {
                        val lx = lEndFrac * width
                        drawLine(NeonPink, Offset(lx, 0f), Offset(lx, height), 2.5f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 5f)))
                        drawCircle(NeonPink, 6f, Offset(lx, height - 10f))
                    }
                }

                // 5. Draw static interactive markers (Cyan color)
                markers.forEach { markerMs ->
                    if (markerMs in startVisibleMs..endVisibleMs) {
                        val frac = (markerMs - startVisibleMs) / (endVisibleMs - startVisibleMs)
                        val x = frac * width
                        drawLine(CyberCyan, Offset(x, 0f), Offset(x, height), 2.0f)
                        drawPolygonTriangle(this, x, 0f, CyberCyan)
                    }
                }

                // 6. Audio current playhead bar
                if (currentPlayheadMs in startVisibleMs..endVisibleMs) {
                    val pFrac = (currentPlayheadMs - startVisibleMs) / (endVisibleMs - startVisibleMs)
                    val px = pFrac * width
                    drawLine(Color.White, Offset(px, 0f), Offset(px, height), 2.0f)
                    drawCircle(Color.White, 5f, Offset(px, 0f))
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Multi scroll bar or Timeline panning support slider
        if (zoom > 1.05f) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scroll-Pan",
                    color = TextMuted,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 6.dp)
                )
                Slider(
                    value = scrollOffset,
                    onValueChange = { scrollOffset = it },
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(
                        thumbColor = CyberCyan,
                        activeTrackColor = SlateMedium,
                        inactiveTrackColor = SlateLight
                    )
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 7. Minimap Overview Panel (Total outline of waveform, highlighted window bounds)
        Text(
            text = "FULL WAVEFORM TIMELINE (MINIMAP)",
            color = TextMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 4.dp),
            fontFamily = FontFamily.Monospace
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(DarkObsidian)
                .pointerInput(track) {
                    detectTapGestures { offset ->
                        val clickedFrac = offset.x / size.width
                        val clickedMs = clickedFrac * durationMs
                        // Center scroll offset on this tap
                        val visibleFracRaw = 1.0f / zoom
                        val startMs = clickedMs - (visibleFracRaw * durationMs / 2f)
                        if (maxScrollOffset > 0f) {
                            scrollOffset = (startMs / maxScrollOffset).coerceIn(0f, 1f)
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val midY = height / 2f

                // Draw whole song minimized waveform
                val numPeaks = minimapPeaks.size
                val pixelStep = width / numPeaks
                for (i in 0 until numPeaks) {
                    val amp = minimapPeaks[i]
                    val x = i * pixelStep
                    val pHeight = max(1f, amp * height * 0.9f)
                    drawLine(
                        color = CyberGreen.copy(alpha = 0.5f),
                        start = Offset(x, midY - pHeight / 2f),
                        end = Offset(x, midY + pHeight / 2f),
                        strokeWidth = 2.0f
                    )
                }

                // Drawing selection region onto minimap
                if (selectionStartMs != null && selectionEndMs != null) {
                    val sx = (selectionStartMs / durationMs) * width
                    val ex = (selectionEndMs / durationMs) * width
                    drawRect(
                        color = VividOrange.copy(alpha = 0.15f),
                        topLeft = Offset(sx, 0f),
                        size = Size(ex - sx, height)
                    )
                }

                // Draw highlighted rectangle for visible viewport
                val visibleFrac = 1.0f / zoom
                val viewportWidth = visibleFrac * width
                val viewportLeft = (startVisibleMs / durationMs) * width
                drawRect(
                    color = CyberCyan.copy(alpha = 0.12f),
                    topLeft = Offset(viewportLeft, 0f),
                    size = Size(viewportWidth, height)
                )
                drawRect(
                    color = CyberCyan,
                    topLeft = Offset(viewportLeft, 0f),
                    size = Size(viewportWidth, height),
                    style = Stroke(width = 1.5f)
                )

                // Draw current playhead position
                val px = (currentPlayheadMs / durationMs) * width
                drawLine(Color.White, Offset(px, 0f), Offset(px, height), 1.5f)
            }
        }
    }
}

private fun drawPolygonTriangle(canvas: androidx.compose.ui.graphics.drawscope.DrawScope, x: Float, y: Float, color: Color) {
    val path = Path().apply {
        moveTo(x, y)
        lineTo(x - 6f, y + 8f)
        lineTo(x + 6f, y + 8f)
        close()
    }
    canvas.drawPath(path, color)
}

/**
 * Real-time fast FFT Spectrum Analyzer view.
 * Displays linear/logarithmic bars, dB reference lines, and dynamic slowly decaying peak-hold dots.
 */
@Composable
fun RealTimeFftSpectrum(
    magnitudes: FloatArray,
    peakFrequency: Float,
    scaleType: String, // "linear" or "logarithmic"
    fftSize: Int,
    significantPeaks: List<Pair<Float, Float>>,
    sampleRate: Int,
    modifier: Modifier = Modifier
) {
    // Maintain a state for peak-hold frequency decays
    val peakArray = remember(fftSize) { FloatArray(fftSize / 2) }
    val lastDecayTime = remember { mutableStateOf(System.currentTimeMillis()) }

    // Update decay slowly inside the frame draw loop
    LaunchedEffect(magnitudes) {
        val now = System.currentTimeMillis()
        val deltaMs = (now - lastDecayTime.value).coerceAtLeast(0L)
        lastDecayTime.value = now

        val decayFactor = exp(-deltaMs / 900.0).toFloat() // decay slowly over 900ms

        for (i in 0 until magnitudes.size.coerceAtMost(peakArray.size)) {
            val live = magnitudes[i]
            val cache = peakArray[i] * decayFactor
            peakArray[i] = max(live, cache)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "REAL-TIME SPECTRUM (${scaleType.uppercase()}) | PEAK: ${peakFrequency.toInt()} Hz",
                color = TextBright,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "RANGE: -80 to 0 dB",
                color = TextMuted,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkObsidian)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val numBins = magnitudes.size

                if (numBins <= 0) return@Canvas

                // 1. Draw dB grid lines (-10dB, -20dB, -40dB, -60dB)
                // We map dynamic range from 0.0001 (approx -80dB) to 1.0 (0dB)
                val dbReferenceLevels = floatArrayOf(-10f, -20f, -30f, -48f, -66f)
                dbReferenceLevels.forEach { db ->
                    // db = 20 * log10(val) => val = 10^(db/20)
                    val amp = 10.0.pow(db / 20.0).toFloat()
                    val gridY = height - (amp * height).coerceAtMost(height)

                    drawLine(GridLineColor, Offset(0f, gridY), Offset(width, gridY), 1f)
                }

                // 2. Draw frequency vertical rules
                // Key target analysis frequencies in Hz
                val frequenciesHz = floatArrayOf(50f, 100f, 250f, 500f, 1000f, 2500f, 5000f, 10000f, 16000f)
                frequenciesHz.forEach { fHz ->
                    val binFrac = fHz / (sampleRate / 2f)
                    val fracX = if (scaleType == "logarithmic") {
                        // logarithmic scaling maps fHz proportionally across octaves
                        val logMin = ln(20.0f)
                        val logMax = ln(22050.0f)
                        val targetLog = ln(fHz.coerceAtLeast(20.0f))
                        ((targetLog - logMin) / (logMax - logMin)).coerceIn(0f, 1f)
                    } else {
                        binFrac.coerceIn(0f, 1f)
                    }

                    val x = fracX * width
                    drawLine(GridLineColor, Offset(x, 0f), Offset(x, height), 1f)
                }

                // Waveform FFT visual gradients
                val barBrush = Brush.verticalGradient(
                    colors = listOf(CyberCyan, CyberGreen),
                    startY = 0f,
                    endY = height
                )

                // 3. Draw active frequency bands
                val limit = numBins.coerceAtMost(peakArray.size)
                if (scaleType == "logarithmic") {
                    // Logarithmic mapping: draw pixel by pixel
                    val logMin = ln(20.0f)
                    val logMax = ln(sampleRate / 2.0f)

                    for (x in 0 until width.toInt()) {
                        val fracX = x.toFloat() / width
                        val curLogFreq = exp(logMin + fracX * (logMax - logMin))
                        val targetBinFloat = (curLogFreq * fftSize) / sampleRate
                        val lowerBin = floor(targetBinFloat).toInt().coerceIn(0, limit - 1)
                        val upperBin = ceil(targetBinFloat).toInt().coerceIn(0, limit - 1)
                        val interpFactor = targetBinFloat - lowerBin

                        // Interpolated spectrum density
                        val liveMag = magnitudes[lowerBin] * (1f - interpFactor) + magnitudes[upperBin] * interpFactor
                        val peakMag = peakArray[lowerBin] * (1f - interpFactor) + peakArray[upperBin] * interpFactor

                        // Translate amplitude to height (with standard scale gains)
                        val gainLive = (liveMag * 4.5f).coerceIn(0f, 1.0f)
                        val gainPeak = (peakMag * 4.5f).coerceIn(0f, 1.0f)

                        val barHeight = gainLive * height
                        val peakY = height - (gainPeak * height)

                        // Live fill bar
                        drawLine(
                            brush = barBrush,
                            start = Offset(x.toFloat(), height),
                            end = Offset(x.toFloat(), height - barHeight),
                            strokeWidth = 1.0f
                        )

                        // Peak hold decaying dot indicators
                        drawCircle(
                            color = NeonPink.copy(alpha = 0.65f),
                            radius = 1.2f,
                            center = Offset(x.toFloat(), peakY)
                        )
                    }
                } else {
                    // Linear layout spectrum bars
                    val barWidth = width / numBins
                    for (i in 0 until limit) {
                        val x = i * barWidth
                        val liveHeight = (magnitudes[i] * 4.5f).coerceIn(0f, 1f) * height
                        val peakY = height - ((peakArray[i] * 4.5f).coerceIn(0f, 1f) * height)

                        // Live bar
                        drawRect(
                            brush = barBrush,
                            topLeft = Offset(x, height - liveHeight),
                            size = Size(barWidth - 1f, liveHeight)
                        )

                        // Peak dot
                        drawCircle(
                            color = NeonPink,
                            radius = 2.0f,
                            center = Offset(x + barWidth / 2f, peakY)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Solid-state layout of 2D waterfall and spectrogram heatmaps of the whole audio selection.
 */
@Composable
fun SpectrogramHeatmap(
    cache: Array<FloatArray>?,
    currentPlayheadMs: Float,
    durationMs: Long,
    scaleType: String,
    sampleRate: Int,
    fftSize: Int,
    modifier: Modifier = Modifier
) {
    var themeColorStyle by remember { mutableStateOf(0) } // 0: Classic Emerald, 1: Cosmic Fire, 2: Cyber Blue

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "TIME-FREQUENCY HEATMAP (SPECTROGRAM)",
                    color = TextBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "Entire song full-resolution spectrogram matrix",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            // Theme selector chips
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Emerald", "Cosmic", "Sky").forEachIndexed { index, name ->
                    Button(
                        onClick = { themeColorStyle = index },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (themeColorStyle == index) CyberCyan else SlateMedium,
                            contentColor = if (themeColorStyle == index) DarkObsidian else TextMuted
                        ),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text(name, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkObsidian)
        ) {
            if (cache == null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = CyberCyan)
                }
            } else {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height

                    val numWindows = cache.size
                    val numBins = cache[0].size

                    if (numWindows <= 0 || numBins <= 0) return@Canvas

                    val stepX = width / numWindows

                    // Predefined color scales
                    val emeraldPalette = listOf(
                        Color(0x000F1015),
                        Color(0xFF003F1F),
                        Color(0xFF008C4A),
                        Color(0xFF00FF88),
                        Color(0xFFCCFFDD)
                    )
                    val cosmicPalette = listOf(
                        Color(0x000F1015),
                        Color(0xFF3F003F),
                        Color(0xFF8C004F),
                        Color(0xFFFF5200),
                        Color(0xFFFFDD44)
                    )
                    val slatePalette = listOf(
                        Color(0x000F1015),
                        Color(0xFF00224F),
                        Color(0xFF00528C),
                        Color(0xFF00E5FF),
                        Color(0xFFE0FFFF)
                    )

                    val activePalette = when (themeColorStyle) {
                        1 -> cosmicPalette
                        2 -> slatePalette
                        else -> emeraldPalette
                    }

                    // Map bin sizes
                    val useLog = (scaleType == "logarithmic")
                    val logMin = ln(20f)
                    val logMax = ln(sampleRate / 2f)

                    for (x in 0 until width.toInt()) {
                        // Map pixel X to source window column idx
                        val winFrac = x.toFloat() / width
                        val winIdx = (winFrac * numWindows).toInt().coerceIn(0, numWindows - 1)
                        val spectrum = cache[winIdx]

                        // Slice y pixel vertical mapping
                        for (y in 0 until height.toInt()) {
                            val yFrac = 1f - (y.toFloat() / height) // bottom is low frequencies
                            val binIdx: Int
                            if (useLog) {
                                val targetF = exp(logMin + yFrac * (logMax - logMin))
                                val targetBin = (targetF * fftSize) / sampleRate
                                binIdx = targetBin.toInt().coerceIn(0, numBins - 1)
                            } else {
                                binIdx = (yFrac * numBins).toInt().coerceIn(0, numBins - 1)
                            }

                            val valDb = spectrum[binIdx] * 4.5f // amplitude scale amplification
                            val normalized = valDb.coerceIn(0f, 1f)

                            // Linear color interpolation
                            val colorIdx = (normalized * (activePalette.size - 1)).toInt().coerceIn(0, activePalette.size - 2)
                            val subFrac = (normalized * (activePalette.size - 1)) - colorIdx
                            val finalColor = Color(
                                red = activePalette[colorIdx].red * (1f - subFrac) + activePalette[colorIdx + 1].red * subFrac,
                                green = activePalette[colorIdx].green * (1f - subFrac) + activePalette[colorIdx + 1].green * subFrac,
                                blue = activePalette[colorIdx].blue * (1f - subFrac) + activePalette[colorIdx + 1].blue * subFrac,
                                alpha = 1.0f
                            )

                            // Draw individual pixel blocks
                            drawRect(
                                color = finalColor,
                                topLeft = Offset(x.toFloat(), y.toFloat()),
                                size = Size(1f, 1f)
                            )
                        }
                    }

                    // 3. Draw vertical playhead mapping onto thermal spectrogram
                    val px = (currentPlayheadMs / durationMs.toFloat()) * width
                    if (px in 0f..width) {
                        drawLine(Color.White.copy(alpha = 0.5f), Offset(px, 0f), Offset(px, height), 1f)
                    }
                }
            }
        }
    }
}

/**
 * Animated neon vectorscope / Lissajous stereo visualizer and scrolling oscilloscope.
 */
@Composable
fun OscilloscopeVectorScope(
    timeDomainSamples: FloatArray,
    activeTrack: DecodedAudio?,
    currentPlayheadMs: Float,
    isSplitStereo: Boolean,
    modifier: Modifier = Modifier
) {
    var viewModeXY by remember { mutableStateOf(false) } // False: Scrolling OSC, True: L-R Lissajous Vectorscope
    val infiniteTransition = rememberInfiniteTransition()
    val scopeNeonPulse by infiniteTransition.animateFloat(
        initialValue = 0.82f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (viewModeXY) "STEREO LASSAJOUS VECTOR FIELD" else "REALTIME ANALOG OSCILLOSCOPE",
                    color = TextBright,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = if (viewModeXY) "Vector width correlation phase plot" else "Continuous time-domain electro-waveform",
                    color = TextMuted,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Button(
                onClick = { viewModeXY = !viewModeXY },
                colors = ButtonDefaults.buttonColors(
                    containerColor = SlateMedium,
                    contentColor = CyberCyan
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier.height(24.dp)
            ) {
                Text(if (viewModeXY) "Show Wave" else "Show Vectors", fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkObsidian)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height

                if (timeDomainSamples.isEmpty()) return@Canvas

                if (viewModeXY && activeTrack != null) {
                    // Lissajous Vectorscope L-R phase mapping
                    // We plot X = Left and Y = Right rotated by 45 degrees
                    val midX = width / 2f
                    val midY = height / 2f
                    val radius = min(midX, midY) * 0.85f

                    // Cross grid overlay lines
                    drawLine(GridLineColor, Offset(midX, 0f), Offset(midX, height), 1f)
                    drawLine(GridLineColor, Offset(0f, midY), Offset(width, midY), 1f)
                    drawLine(GridLineColor, Offset(midX - radius, midY - radius), Offset(midX + radius, midY + radius), 0.7f)
                    drawLine(GridLineColor, Offset(midX - radius, midY + radius), Offset(midX + radius, midY - radius), 0.7f)

                    // Draw actual points
                    val sizeToScan = timeDomainSamples.size
                    val sampleRate = activeTrack.sampleRate
                    val playheadSec = currentPlayheadMs / 1000f
                    val startIdx = (playheadSec * sampleRate).toInt().coerceIn(0, activeTrack.dataL.size - sizeToScan)

                    val pL = activeTrack.dataL
                    val pR = activeTrack.dataR

                    val brushL = Brush.radialGradient(
                        colors = listOf(CyberCyan, Color.Transparent),
                        center = Offset(midX, midY),
                        radius = radius * 1.5f
                    )

                    val pathVec = Path()
                    var first = true

                    for (i in 0 until sizeToScan step 2) {
                        val activeIdx = startIdx + i
                        if (activeIdx >= pL.size) break

                        val sigL = pL[activeIdx]
                        val sigR = pR[activeIdx]

                        // Rotational matrices creating traditional Goniometer scope orientation
                        // X = (L - R) / sqrt(2), Y = (L + R) / sqrt(2)
                        val rotX = (sigL - sigR) / 1.4142f
                        val rotY = (sigL + sigR) / 1.4142f

                        val drawX = midX + rotX * radius * scopeNeonPulse
                        val drawY = midY - rotY * radius * scopeNeonPulse // Canvas coords inverted vertically

                        if (first) {
                            pathVec.moveTo(drawX, drawY)
                            first = false
                        } else {
                            pathVec.lineTo(drawX, drawY)
                        }
                    }

                    drawPath(
                        path = pathVec,
                        color = CyberCyan.copy(alpha = 0.75f),
                        style = Stroke(width = 1.5f)
                    )
                } else {
                    // Standard Scrolling 1D continuous Oscilloscope rendering
                    val midY = height / 2f
                    // Draw horizontal baseline
                    drawLine(GridLineColor, Offset(0f, midY), Offset(width, midY), 1f)

                    val points = timeDomainSamples.size
                    val pathOsc = Path()
                    val dx = width / points

                    for (i in 0 until points) {
                        val sample = timeDomainSamples[i]
                        val x = i * dx
                        val y = midY - (sample * midY * 0.88f * scopeNeonPulse)

                        if (i == 0) {
                            pathOsc.moveTo(x, y)
                        } else {
                            pathOsc.lineTo(x, y)
                        }
                    }

                    drawPath(
                        path = pathOsc,
                        brush = Brush.linearGradient(
                            listOf(CyberGreen, CyberCyan),
                            start = Offset(0f, midY),
                            end = Offset(width, midY)
                        ),
                        style = Stroke(width = 2.0f)
                    )
                }
            }
        }
    }
}

/**
 * Animated Radially symmetric circular oscilloscope.
 */
@Composable
fun CircularRadarVisualizer(
    magnitudes: FloatArray,
    timeDomainSamples: FloatArray,
    isPlaying: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition()
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(22000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        )
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Text(
            text = "SYMMETRICAL RADAR COMPASS",
            color = TextBright,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 6.dp),
            fontFamily = FontFamily.Monospace
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(DarkObsidian),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val midX = width / 2f
                val midY = height / 2f
                val baseRadius = min(midX, midY) * 0.45f

                // Draw secondary radar rings
                drawCircle(GridLineColor, baseRadius * 0.5f, Offset(midX, midY), style = Stroke(1f))
                drawCircle(GridLineColor, baseRadius, Offset(midX, midY), style = Stroke(1f))
                drawCircle(GridLineColor, baseRadius * 1.5f, Offset(midX, midY), style = Stroke(1f))

                // Compass lines
                for (angle in 0 until 360 step 45) {
                    val rad = Math.toRadians(angle.toDouble() + rotationAngle)
                    val outerX = midX + cos(rad).toFloat() * baseRadius * 1.8f
                    val outerY = midY + sin(rad).toFloat() * baseRadius * 1.8f
                    drawLine(GridLineColor, Offset(midX, midY), Offset(outerX, outerY), 0.8f)
                }

                // Compile radial waveform path
                val count = magnitudes.size
                if (count > 0) {
                    val pathRadar = Path()
                    var first = true

                    for (i in 0 until 360 step 2) {
                        val binIdx = ((i / 360f) * count).toInt().coerceIn(0, count - 1)
                        // Magnifying envelopes
                        val spectrumEnergy = magnitudes[binIdx] * 3.8f
                        val radialAmplitude = baseRadius + min(baseRadius, spectrumEnergy * baseRadius * 0.7f)

                        val rad = Math.toRadians(i.toDouble() + rotationAngle)
                        val rx = midX + cos(rad).toFloat() * radialAmplitude
                        val ry = midY + sin(rad).toFloat() * radialAmplitude

                        if (first) {
                            pathRadar.moveTo(rx, ry)
                            first = false
                        } else {
                            pathRadar.lineTo(rx, ry)
                        }
                    }
                    pathRadar.close()

                    drawPath(
                        path = pathRadar,
                        brush = Brush.sweepGradient(
                            colors = listOf(CyberCyan, CyberGreen, VividOrange, CyberCyan),
                            center = Offset(midX, midY)
                        ),
                        style = Stroke(width = 2.0f)
                    )
                }
            }
        }
    }
}

/**
 * Standard Stereo Peak dB Volume level meter and clipping indicator alarms.
 */
@Composable
fun StereoVUMeters(
    rmsDbL: Float,
    rmsDbR: Float,
    clippingDetected: Boolean,
    modifier: Modifier = Modifier
) {
    // Smoothe meters with standard spring physics
    val smoothDbL by animateFloatAsState(
        targetValue = rmsDbL.coerceIn(-60f, 0f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
    )
    val smoothDbR by animateFloatAsState(
        targetValue = rmsDbR.coerceIn(-60f, 0f),
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessHigh)
    )

    Column(
        modifier = modifier
            .fillMaxHeight()
            .background(SlateGraphite, RoundedCornerShape(24.dp))
            .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
            .padding(12.dp)
            .width(64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
            // Clipping LED Indicators
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (clippingDetected) NeonPink else Color(0xFF330B1C)),
                contentAlignment = Alignment.Center
            ) {
                Text("CLIP", color = if (clippingDetected) DarkObsidian else NeonPink.copy(alpha=0.3f), fontSize = 7.sp, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Stereo DB Indicators
            Row(
                modifier = Modifier.fillMaxHeight().weight(1f).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Left strip
                VUMeterBar(smoothDbL, "L")
                // Right strip
                VUMeterBar(smoothDbR, "R")
            }
        }
    }
}

@Composable
fun VUMeterBar(dbValue: Float, label: String) {
    // Mapping: -60dB -> 0f, 0dB -> 1f
    val fraction = ((dbValue + 60f) / 60f).coerceIn(0f, 1f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxHeight()
    ) {
        Box(
            modifier = Modifier
                .width(10.dp)
                .fillMaxHeight()
                .weight(1f)
                .clip(RoundedCornerShape(3.dp))
                .background(DarkObsidian)
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val barW = size.width
                val barH = size.height

                val fillHeight = fraction * barH

                // Colors: green -> orange -> red top section
                val fills = Brush.verticalGradient(
                    colors = listOf(
                        NeonPink,
                        VividOrange,
                        CyberGreen,
                        CyberGreen
                    ),
                    startY = 0f,
                    endY = barH
                )

                drawRect(
                    brush = fills,
                    topLeft = Offset(0f, barH - fillHeight),
                    size = Size(barW, fillHeight)
                )

                // Decibel divider ticks every -10dB
                for (db in 1 until 6) {
                    val tickY = (db / 6f) * barH
                    drawLine(SlateGraphite, Offset(0f, tickY), Offset(barW, tickY), 2.0f)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(label, color = TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}
