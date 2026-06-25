package com.itsme.amkush.ui

import android.net.Uri
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.itsme.amkush.utils.Logger
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

private val BgCard  = Color(0xFF0E0E16)
private val Violet  = Color(0xFF6C63FF)
private val RedErr  = Color(0xFFFF4D6D)
private val YellWarn= Color(0xFFFACC15)
private val GreenOk = Color(0xFF4ADE80)
private val TextSec = Color(0x44FFFFFF)
private val TextMid = Color(0x88FFFFFF)

@Composable
fun StreamPlayerDialog(url: String, onClose: () -> Unit) {
    val context = LocalContext.current

    var status  by remember { mutableStateOf("Connecting...") }
    var statusColor by remember { mutableStateOf(YellWarn) }
    var isError by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }

    var libVLC: LibVLC? by remember { mutableStateOf(null) }
    var mediaPlayer: MediaPlayer? by remember { mutableStateOf(null) }
    var videoLayout: VLCVideoLayout? by remember { mutableStateOf(null) }

    DisposableEffect(url) {
        val args = arrayListOf(
            "--no-audio", "--no-stats", "--no-sub-autodetect-file",
            "--no-osd", "--network-caching=300", "--live-caching=300",
            "--file-caching=300", "--input-repeat=-1"
        )
        val vlc = LibVLC(context, args)
        val mp  = MediaPlayer(vlc)

        mp.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    status = "LIVE"
                    statusColor = GreenOk
                    isError = false
                    isLoading = false
                }
                MediaPlayer.Event.EncounteredError -> {
                    status = "Stream error"
                    statusColor = RedErr
                    isError = true
                    isLoading = false
                }
                else -> {}
            }
        }

        libVLC = vlc
        mediaPlayer = mp

        try {
            val media = Media(vlc, Uri.parse(url))
            media.addOption(":network-caching=300")
            media.addOption(":live-caching=300")
            mp.setMedia(media)

            videoLayout?.let { vl ->
                mp.attachViews(vl, null, false, false)
                mp.play()
            }
        } catch (e: Exception) {
            Logger.e("VLC start error", e)
            status = "Error: ${e.message}"
            statusColor = RedErr
            isError = true
            isLoading = false
        }

        onDispose {
            mp.stop()
            mp.detachViews()
            mp.release()
            vlc.release()
        }
    }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xBF000000))
                .clickable(
                    indication = null,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                ) { onClose() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(24.dp))
                    .background(BgCard)
                    .clickable(
                        indication = null,
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                    ) {}
            ) {
                Column {
                    // 16:9 Video area
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .background(Color.Black)
                    ) {
                        AndroidView(
                            factory = { ctx ->
                                VLCVideoLayout(ctx).also { vl ->
                                    videoLayout = vl
                                    mediaPlayer?.let { mp ->
                                        try {
                                            val media = Media(libVLC, Uri.parse(url))
                                            media.addOption(":network-caching=300")
                                            media.addOption(":live-caching=300")
                                            mp.setMedia(media)
                                            mp.attachViews(vl, null, false, false)
                                            mp.play()
                                        } catch (e: Exception) {
                                            Logger.e("VLC attach error", e)
                                        }
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Loading overlay
                        if (isLoading && !isError) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    CircularProgressIndicator(
                                        color = Violet,
                                        modifier = Modifier.size(40.dp),
                                        strokeWidth = 2.dp
                                    )
                                    Text("Buffering stream...", color = Violet.copy(0.7f),
                                        fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                                }
                            }
                        }

                        // Error overlay
                        if (isError) {
                            Box(
                                modifier = Modifier.fillMaxSize().background(Color.Black),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("⚠", fontSize = 32.sp, color = RedErr)
                                    Text("Unable to play stream", color = RedErr, fontSize = 14.sp)
                                }
                            }
                        }
                    }

                    // Status row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(status, color = statusColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        Text(
                            url,
                            color = TextSec,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false).padding(horizontal = 8.dp)
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0x1AFFFFFF))
                                .clickable { onClose() }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Close", color = TextMid, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
