package com.itsme.amkush.ui

  import android.content.Context
  import androidx.compose.foundation.background
  import androidx.compose.foundation.border
  import androidx.compose.foundation.clickable
  import androidx.compose.foundation.layout.*
  import androidx.compose.foundation.shape.CircleShape
  import androidx.compose.foundation.shape.RoundedCornerShape
  import androidx.compose.material3.CircularProgressIndicator
  import androidx.compose.material3.Text
  import androidx.compose.runtime.*
  import androidx.compose.ui.Alignment
  import androidx.compose.ui.Modifier
  import androidx.compose.ui.draw.clip
  import androidx.compose.ui.graphics.Color
  import androidx.compose.ui.text.font.FontFamily
  import androidx.compose.ui.text.font.FontWeight
  import androidx.compose.ui.unit.dp
  import androidx.compose.ui.unit.sp
  import androidx.compose.ui.viewinterop.AndroidView
  import androidx.compose.ui.window.Dialog
  import androidx.compose.ui.window.DialogProperties
  import org.videolan.libvlc.LibVLC
  import org.videolan.libvlc.Media
  import org.videolan.libvlc.MediaPlayer
  import org.videolan.libvlc.util.VLCVideoLayout

  private val Violet  = Color(0xFF6C63FF)
  private val Pink    = Color(0xFFFF4D9D)
  private val BgDark  = Color(0xFF0D0D18)
  private val Surface = Color(0x12FFFFFF)
  private val Border  = Color(0x1AFFFFFF)
  private val TextMid = Color(0x88FFFFFF)

  @Composable
  fun StreamPreviewDialog(url: String, onDismiss: () -> Unit) {
      var buffering by remember { mutableStateOf(true) }
      var error     by remember { mutableStateOf<String?>(null) }

      // Hold refs so DisposableEffect and AndroidView share the same instances
      val vlcRef = remember { mutableStateOf<LibVLC?>(null) }
      val mpRef  = remember { mutableStateOf<MediaPlayer?>(null) }

      // Create LibVLC + MediaPlayer once; clean up on dismiss
      DisposableEffect(url) {
          val options = arrayListOf(
              "--no-drop-late-frames", "--no-skip-frames",
              "--rtsp-tcp", "--network-caching=200",
              "--live-caching=200", "--file-caching=200",
              "-vvv"
          )
          val libVLC = LibVLC(null, options)   // null context — attach surfaces manually
          val mp     = MediaPlayer(libVLC)

          mp.setEventListener { event ->
              when (event.type) {
                  MediaPlayer.Event.Buffering  -> buffering = event.buffering < 100f
                  MediaPlayer.Event.Playing    -> buffering = false
                  MediaPlayer.Event.EncounteredError -> error = "Stream error — check URL"
                  MediaPlayer.Event.EndReached -> onDismiss()
              }
          }

          vlcRef.value = libVLC
          mpRef.value  = mp

          onDispose {
              mp.stop(); mp.detachViews(); mp.release()
              libVLC.release()
              vlcRef.value = null; mpRef.value = null
          }
      }

      Dialog(
          onDismissRequest = onDismiss,
          properties = DialogProperties(usePlatformDefaultWidth = false)
      ) {
          Box(
              modifier = Modifier
                  .fillMaxWidth(0.95f)
                  .clip(RoundedCornerShape(20.dp))
                  .background(BgDark)
                  .border(1.dp, Border, RoundedCornerShape(20.dp))
          ) {
              Column {
                  // Title bar
                  Row(
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 16.dp, vertical = 12.dp),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                      Column {
                          Text("Stream Preview", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                          Text(url.take(40) + if (url.length > 40) "…" else "",
                              color = TextMid, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                      }
                      Box(
                          modifier = Modifier
                              .size(28.dp)
                              .clip(CircleShape)
                              .background(Color(0x1AFFFFFF))
                              .clickable { onDismiss() },
                          contentAlignment = Alignment.Center
                      ) {
                          Text("✕", color = TextMid, fontSize = 12.sp)
                      }
                  }

                  // Video surface — BUG FIX: attach player inside AndroidView's update block
                  // so the view definitely exists before attachViews() is called
                  Box(
                      modifier = Modifier
                          .fillMaxWidth()
                          .height(220.dp)
                          .background(Color.Black),
                      contentAlignment = Alignment.Center
                  ) {
                      AndroidView(
                          factory = { ctx: Context ->
                              VLCVideoLayout(ctx)
                          },
                          update = { layout ->
                              val mp = mpRef.value ?: return@AndroidView
                              // Attach views only once (when not already playing)
                              if (!mp.isPlaying && error == null) {
                                  try {
                                      mp.attachViews(layout, null, false, false)
                                      val media = Media(vlcRef.value, android.net.Uri.parse(url))
                                      mp.media = media
                                      media.release()
                                      mp.play()
                                  } catch (e: Exception) {
                                      error = "Failed to start: ${e.message}"
                                  }
                              }
                          },
                          modifier = Modifier.fillMaxSize()
                      )

                      // Buffering overlay
                      if (buffering && error == null) {
                          Column(
                              horizontalAlignment = Alignment.CenterHorizontally,
                              verticalArrangement = Arrangement.spacedBy(8.dp)
                          ) {
                              CircularProgressIndicator(color = Violet, modifier = Modifier.size(32.dp), strokeWidth = 2.dp)
                              Text("Buffering…", color = TextMid, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                          }
                      }

                      // Error overlay
                      if (error != null) {
                          Column(
                              horizontalAlignment = Alignment.CenterHorizontally,
                              verticalArrangement = Arrangement.spacedBy(8.dp)
                          ) {
                              Text("⚠", fontSize = 28.sp)
                              Text(error!!, color = Color(0xFFFF4D6D), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                              Box(
                                  modifier = Modifier
                                      .clip(RoundedCornerShape(8.dp))
                                      .background(Color(0x1AFF4D6D))
                                      .clickable { onDismiss() }
                                      .padding(horizontal = 16.dp, vertical = 8.dp)
                              ) {
                                  Text("Close", color = Color(0xFFFF4D6D), fontSize = 12.sp)
                              }
                          }
                      }
                  }

                  // Bottom controls
                  Row(
                      modifier = Modifier
                          .fillMaxWidth()
                          .padding(horizontal = 16.dp, vertical = 12.dp),
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                      // Stop button
                      Box(
                          modifier = Modifier
                              .clip(RoundedCornerShape(10.dp))
                              .background(Color(0x1AFFFFFF))
                              .border(1.dp, Border, RoundedCornerShape(10.dp))
                              .clickable { mpRef.value?.stop(); onDismiss() }
                              .padding(horizontal = 14.dp, vertical = 8.dp),
                          contentAlignment = Alignment.Center
                      ) {
                          Text("Stop & Close", color = TextMid, fontSize = 12.sp)
                      }
                      // Status chip
                      val statusColor = when {
                          error != null -> Color(0xFFFF4D6D)
                          buffering     -> Color(0xFFFACC15)
                          else          -> Color(0xFF4ADE80)
                      }
                      val statusText = when {
                          error != null -> "ERROR"
                          buffering     -> "BUFFERING"
                          else          -> "LIVE"
                      }
                      Box(
                          modifier = Modifier
                              .clip(RoundedCornerShape(10.dp))
                              .background(statusColor.copy(0.15f))
                              .border(1.dp, statusColor.copy(0.3f), RoundedCornerShape(10.dp))
                              .padding(horizontal = 14.dp, vertical = 8.dp),
                          contentAlignment = Alignment.Center
                      ) {
                          Text(statusText, color = statusColor, fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                      }
                  }
              }
          }
      }
  }
  