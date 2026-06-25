package com.itsme.amkush.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class SplashScreenActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SplashContent(onNavigate = {
                val intent = Intent(this, HomeScreen::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                finish()
            })
        }
    }
}

@Composable
private fun SplashContent(onNavigate: () -> Unit) {
    val bgColor = Color(0xFF0D0D18)
    val violet  = Color(0xFF6C63FF)
    val green   = Color(0xFF4ADE80)

    var showUser    by remember { mutableStateOf(false) }
    var showPrompt  by remember { mutableStateOf(false) }
    var showCommand by remember { mutableStateOf(false) }
    var showCheck   by remember { mutableStateOf(false) }
    var cursorAlpha by remember { mutableFloatStateOf(1f) }

    val cursorAnim = rememberInfiniteTransition(label = "cursor")
    val blink by cursorAnim.animateFloat(
        initialValue = 1f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "blink"
    )
    val checkScale by animateFloatAsState(
        targetValue = if (showCheck) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "checkScale"
    )

    LaunchedEffect(Unit) {
        delay(300)
        showUser = true
        delay(150)
        showPrompt = true
        delay(150)
        showCommand = true
        delay(600)
        showCheck = true
        delay(1300)
        onNavigate()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bgColor),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedVisibility(visible = showUser, enter = fadeIn()) {
                    Text(
                        text = "user@FaceGate",
                        color = green,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                AnimatedVisibility(visible = showPrompt, enter = fadeIn()) {
                    Text(
                        text = ":~❯ ",
                        color = violet,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
                AnimatedVisibility(visible = showCommand, enter = fadeIn()) {
                    Text(
                        text = "Happy Hooking",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                }
                if (showCommand && !showCheck) {
                    Text(
                        text = "█",
                        color = Color.White.copy(alpha = blink),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 18.sp
                    )
                }
            }
            if (showCheck) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = "✔",
                    color = green,
                    fontSize = (22 * checkScale).sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
