package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.audio.*
import com.example.ui.components.*
import com.example.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioAnalyzerScreen() {
    val context = LocalContext.current

    // Observe state flows from AudioEngine
    val isPlaying by AudioEngine.isPlaying.collectAsStateWithLifecycle()
    val playheadMs by AudioEngine.currentPlayheadMs.collectAsStateWithLifecycle()
    val volume by AudioEngine.volume.collectAsStateWithLifecycle()
    val speed by AudioEngine.speed.collectAsStateWithLifecycle()
    val isLooping by AudioEngine.isLooping.collectAsStateWithLifecycle()
    val loopStartMs by AudioEngine.loopStartMs.collectAsStateWithLifecycle()
    val loopEndMs by AudioEngine.loopEndMs.collectAsStateWithLifecycle()
    val fftSize by AudioEngine.fftSize.collectAsStateWithLifecycle()
    val scaleType by AudioEngine.scaleType.collectAsStateWithLifecycle()
    val activeTrack by AudioEngine.activeTrack.collectAsStateWithLifecycle()
    val activeBpm by AudioEngine.activeBpm.collectAsStateWithLifecycle()
    val playlist by AudioEngine.playlist.collectAsStateWithLifecycle()
    val loadingProgress by AudioEngine.loadingProgress.collectAsStateWithLifecycle()

    // Real-time telemetry values
    val rmsDbL by AudioEngine.rmsDbL.collectAsStateWithLifecycle()
    val rmsDbR by AudioEngine.rmsDbR.collectAsStateWithLifecycle()
    val lufs by AudioEngine.lufs.collectAsStateWithLifecycle()
    val peakFreq by AudioEngine.peakFreq.collectAsStateWithLifecycle()
    val stereoBalance by AudioEngine.stereoBalance.collectAsStateWithLifecycle()
    val clippingDetected by AudioEngine.clippingDetected.collectAsStateWithLifecycle()
    val isSilence by AudioEngine.isSilence.collectAsStateWithLifecycle()
    val significantPeaks by AudioEngine.significantPeaks.collectAsStateWithLifecycle()

    // Interactive navigation states
    val selectionStartMs by AudioEngine.selectionStartMs.collectAsStateWithLifecycle()
    val selectionEndMs by AudioEngine.selectionEndMs.collectAsStateWithLifecycle()
    val markers by AudioEngine.markerMsList.collectAsStateWithLifecycle()

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { AudioEngine.loadUserAudioUri(context, it) }
    }

    var selectedTab by remember { mutableStateOf(0) } // 0: Main DAW Desk, 1: Scope & Radar, 2: Giant Spectrogram
    var playlistExpanded by remember { mutableStateOf(false) }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkObsidian)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(DarkObsidian)
        ) {
            // 1. TOP HEADER BANNER
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateMedium)
                    .drawBehind {
                        drawLine(
                            color = SlateLight,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 3f
                        )
                    }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Styled "A" Brand Logo Block
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(SleekBlue, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "A",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "AudioPro",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.5.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            // Dynamic active LED lamp
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(if (isPlaying) CyberGreen else TextMuted, shape = RoundedCornerShape(4.dp))
                            )
                        }
                        Text(
                            text = "V2.4.0 ANALYZER",
                            color = TextMuted,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = (-0.5).sp
                        )
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Dropdown playlist triggers
                    Box {
                        Button(
                            onClick = { playlistExpanded = !playlistExpanded },
                            colors = ButtonDefaults.buttonColors(containerColor = SlateLight, contentColor = TextBright),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            modifier = Modifier.height(38.dp)
                        ) {
                            Icon(Icons.Default.Menu, "Playlist", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                activeTrack?.name ?: "No asset loaded",
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 120.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = playlistExpanded,
                            onDismissRequest = { playlistExpanded = false },
                            modifier = Modifier.background(SlateMedium)
                        ) {
                            playlist.forEach { track ->
                                DropdownMenuItem(
                                    text = { Text(track.name, color = TextBright, fontSize = 12.sp, maxLines = 1) },
                                    onClick = {
                                        AudioEngine.selectTrack(track)
                                        playlistExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Import button
                    Button(
                        onClick = { filePickerLauncher.launch("audio/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = SleekBlue, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Outlined.FolderOpen, "Import", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("IMPORT", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }

                    // Auxiliary quick buttons from design (Decorative/helpful UI elements)
                    IconButton(
                        onClick = { /* Settings context */ },
                        modifier = Modifier
                            .size(38.dp)
                            .background(SlateLight, shape = RoundedCornerShape(19.dp))
                    ) {
                        Icon(Icons.Default.Settings, "Settings", tint = TextMuted, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // 2. IMPORT DECODING PROGRESS DIALOG
            loadingProgress?.let { progress ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SlateMedium)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        CircularProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.size(18.dp),
                            color = CyberCyan,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Decompressing audio stream... ${(progress * 100).toInt()}% done",
                            color = TextBright,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.width(100.dp).height(4.dp),
                            color = CyberCyan,
                            trackColor = SlateLight
                        )
                    }
                }
            }

            // 3. MAIN DASHBOARD TABS (Sleek Interface custom pill tabs)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(DarkObsidian)
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier
                        .background(SlateMedium, RoundedCornerShape(16.dp))
                        .border(1.dp, SlateLight, RoundedCornerShape(16.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tabItems = listOf(
                        Triple(0, "SPECTRAL DESK", Icons.Default.Dashboard),
                        Triple(1, "OSCILLATION VECTORS", Icons.Default.GraphicEq),
                        Triple(2, "GIANT SPECTROGRAM", Icons.Default.Waves)
                    )

                    tabItems.forEach { (index, title, icon) ->
                        val selected = (selectedTab == index)
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (selected) SlateGraphite else Color.Transparent,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .then(
                                    if (selected) Modifier.border(1.dp, SlateLight, RoundedCornerShape(12.dp))
                                    else Modifier
                                )
                                .clickable { selectedTab = index }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = title,
                                        tint = if (selected) Color.White else TextMuted,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = title,
                                        color = if (selected) Color.White else TextMuted,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                if (selected) {
                                    Spacer(modifier = Modifier.height(2.dp))
                                    // Custom active indicator dot mimicking the mockup glow
                                    Box(
                                        modifier = Modifier
                                            .size(5.dp)
                                            .background(SleekBlue, shape = RoundedCornerShape(2.5.dp))
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 4. MAIN DOCKABLE CONTENT AREA
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Secondary Left Visual Panels
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeTrack?.let { track ->
                        when (selectedTab) {
                            0 -> {
                                // Tab 0: Stereo waveform + real-time FFT spectrum overlay
                                StereoWaveformWithMinimap(
                                    track = track,
                                    currentPlayheadMs = playheadMs,
                                    loopStartMs = loopStartMs,
                                    loopEndMs = loopEndMs,
                                    isLooping = isLooping,
                                    onSeek = { AudioEngine.setPlayhead(it) },
                                    onSetLoop = { s, e -> AudioEngine.setLoopRegion(s, e) },
                                    selectionStartMs = selectionStartMs,
                                    selectionEndMs = selectionEndMs,
                                    onSetSelection = { s, e ->
                                        AudioEngine.selectionStartMs.value = s
                                        AudioEngine.selectionEndMs.value = e
                                        if (s != null && e != null) {
                                            AudioEngine.setLoopRegion(s, e)
                                        }
                                    },
                                    markers = markers,
                                    onAddMarker = { AudioEngine.addMarkerAtCurrent() },
                                    onRemoveMarker = { AudioEngine.removeMarker(it) }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    RealTimeFftSpectrum(
                                        magnitudes = AudioEngine.magnitudes,
                                        peakFrequency = peakFreq,
                                        scaleType = scaleType,
                                        fftSize = fftSize,
                                        significantPeaks = significantPeaks,
                                        sampleRate = track.sampleRate,
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            1 -> {
                                // Tab 1: Symmetrical oscilloscope, Lissajous phase, and Symmetrical radar compass
                                OscilloscopeVectorScope(
                                    timeDomainSamples = AudioEngine.timeDomainSamples,
                                    activeTrack = track,
                                    currentPlayheadMs = playheadMs,
                                    isSplitStereo = false
                                )

                                CircularRadarVisualizer(
                                    magnitudes = AudioEngine.magnitudes,
                                    timeDomainSamples = AudioEngine.timeDomainSamples,
                                    isPlaying = isPlaying
                                )
                            }

                            2 -> {
                                // Tab 2: Full-length detailed scrollable multi-palette spectrogram
                                SpectrogramHeatmap(
                                    cache = AudioEngine.spectrogramCache,
                                    currentPlayheadMs = playheadMs,
                                    durationMs = track.durationMs,
                                    scaleType = scaleType,
                                    sampleRate = track.sampleRate,
                                    fftSize = fftSize
                                )

                                // Fast spectrogram precalc warning
                                val precalcProg by AudioEngine.spectrogramProgress.collectAsStateWithLifecycle()
                                if (precalcProg < 1.0f) {
                                    Card(
                                        colors = CardDefaults.cardColors(containerColor = SlateMedium),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 1.5.dp, color = CyberCyan)
                                            Spacer(modifier = Modifier.width(12.dp))
                                            Text(
                                                "Precalculating background FFT spectrogram matrix cache: ${(precalcProg * 100).toInt()}% completed",
                                                color = TextMuted,
                                                fontSize = 10.sp,
                                                fontFamily = FontFamily.Monospace
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } ?: Box(
                        modifier = Modifier.fillMaxSize().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Select or import a track to start audio telemetry.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // RIGHT PANEL: Advanced Telemetry Meters & Diagnostic outputs
                Column(
                    modifier = Modifier
                        .width(160.dp)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    activeTrack?.let { track ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Vertical VU dB Level meters bar
                            StereoVUMeters(
                                rmsDbL = rmsDbL,
                                rmsDbR = rmsDbR,
                                clippingDetected = clippingDetected,
                                modifier = Modifier.fillMaxHeight()
                            )

                            // Telemetry metrics readout board
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .background(SlateGraphite, RoundedCornerShape(24.dp))
                                    .border(1.dp, SlateLight, RoundedCornerShape(24.dp))
                                    .padding(12.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("READOUTS", color = TextBright, fontSize = 9.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)

                                TelemetryMetricValue("PEAK L", "${rmsDbL.toInt()} dB", if (clippingDetected) NeonPink else CyberCyan)
                                TelemetryMetricValue("PEAK R", "${rmsDbR.toInt()} dB", if (clippingDetected) NeonPink else CyberGreen)
                                TelemetryMetricValue("RMS DB", "${((rmsDbL + rmsDbR)/2f).toInt()} dB", TextBright)
                                TelemetryMetricValue("LUFS S-T", lufs.formatLufsValue(), if (lufs > -14f) NeonPink else CyberCyan)
                                TelemetryMetricValue("CORR COR", String.format("%.2f", stereoBalance), if (stereoBalance > 0.8f) CyberGreen else VividOrange)
                                TelemetryMetricValue("BPM EST", "${activeBpm.toInt()}", CyberCyan)
                                TelemetryMetricValue("PEAK HZ", "${peakFreq.toInt()} Hz", CyberGreen)

                                Spacer(modifier = Modifier.height(4.dp))

                                // Active Harmonic lists overlay
                                Text("HARMONICS", color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                significantPeaks.take(3).forEach { peak ->
                                    Text(
                                        text = "• ${peak.first.toInt()}Hz",
                                        color = CyberGreen,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    } ?: Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SlateGraphite, RoundedCornerShape(24.dp))
                            .border(1.dp, SlateLight, RoundedCornerShape(24.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("No data", color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }

            // 5. SCREEN CONTROL FOOTER BAR (Sleek Interface bottom controls dock)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SlateMedium)
                    .drawBehind {
                        drawLine(
                            color = SlateLight,
                            start = Offset(0f, 0f),
                            end = Offset(size.width, 0f),
                            strokeWidth = 2f
                        )
                    }
                    .padding(horizontal = 24.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    // Playback actions
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Centered High-contrast Pure White Play button
                        IconButton(
                            onClick = {
                                if (isPlaying) AudioEngine.pause() else AudioEngine.play()
                            },
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color.White, shape = RoundedCornerShape(21.dp))
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = "Play/Pause",
                                tint = DarkObsidian,
                                modifier = Modifier.size(22.dp)
                            )
                        }

                        // Stop Action Button
                        IconButton(
                            onClick = { AudioEngine.stop() },
                            modifier = Modifier
                                .size(36.dp)
                                .background(SlateLight, shape = RoundedCornerShape(18.dp))
                                .border(1.dp, SlateLight, RoundedCornerShape(18.dp))
                        ) {
                            Icon(Icons.Default.Stop, "Stop", tint = NeonPink, modifier = Modifier.size(16.dp))
                        }

                        // Loop toggle box with glowing blue selection backplane
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(
                                    color = if (isLooping) SleekBlue.copy(alpha = 0.15f) else SlateLight,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .border(
                                    width = 1.dp,
                                    color = if (isLooping) SleekBlue else SlateLight,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable { AudioEngine.setLooping(!isLooping) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Loop,
                                "Loop",
                                tint = if (isLooping) CyberCyan else TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Marker anchor tagging
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(SlateLight, shape = RoundedCornerShape(18.dp))
                                .border(1.dp, SlateLight, RoundedCornerShape(18.dp))
                                .clickable { AudioEngine.addMarkerAtCurrent() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Default.Add, "Add Marker", tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                    }

                    // FFT sizes, scale systems, volume levels
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // FFT Size
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(SlateLight, RoundedCornerShape(10.dp))
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "FFT",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            listOf(512, 1024, 2048).forEach { size ->
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.dp)
                                        .background(
                                            color = if (fftSize == size) SleekBlue else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { AudioEngine.changeFftSize(size) }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "$size",
                                        color = if (fftSize == size) Color.White else TextMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // Grid Scale
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .background(SlateLight, RoundedCornerShape(10.dp))
                                .padding(4.dp)
                        ) {
                            Text(
                                text = "SCALE",
                                color = TextMuted,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            listOf("LOG", "LIN").forEach { scale ->
                                val active = (scale == "LOG" && scaleType == "logarithmic") || (scale == "LIN" && scaleType == "linear")
                                Box(
                                    modifier = Modifier
                                        .padding(horizontal = 1.dp)
                                        .background(
                                            color = if (active) SleekBlue else Color.Transparent,
                                            shape = RoundedCornerShape(8.dp)
                                        )
                                        .clickable { AudioEngine.setScaleType(if (scale == "LOG") "logarithmic" else "linear") }
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = scale,
                                        color = if (active) Color.White else TextMuted,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        // Playback Speed Controls
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "SPEED",
                                color = TextMuted,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                            Slider(
                                value = speed,
                                onValueChange = { AudioEngine.setSpeed(it) },
                                valueRange = 0.5f..2.0f,
                                modifier = Modifier.width(80.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = SleekBlue,
                                    inactiveTrackColor = SlateLight
                                )
                            )
                            Text(
                                text = String.format("%.1fx", speed),
                                color = TextBright,
                                fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.width(32.dp),
                                textAlign = TextAlign.End
                            )
                        }

                        // Master Volume Slider
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(Icons.Default.VolumeUp, "Volume", tint = TextMuted, modifier = Modifier.size(16.dp))
                            Slider(
                                value = volume,
                                onValueChange = { AudioEngine.setVolume(it) },
                                valueRange = 0f..1.0f,
                                modifier = Modifier.width(72.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.White,
                                    activeTrackColor = SleekBlue,
                                    inactiveTrackColor = SlateLight
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TelemetryMetricValue(label: String, value: String, color: Color) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = TextMuted, fontSize = 8.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(
            value,
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.Black,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Clip
        )
    }
}

private fun Float.formatLufsValue(): String {
    return if (this <= -119f) "-∞ LUFS" else String.format("%.1f LUFS", this)
}
