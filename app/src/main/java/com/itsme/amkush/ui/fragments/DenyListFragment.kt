package com.itsme.amkush.ui.fragments

import android.annotation.SuppressLint
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import com.itsme.amkush.model.AppInfo
import com.itsme.amkush.ui.AppIconCircle
import com.itsme.amkush.utils.SharedPrefs
import kotlinx.coroutines.*

private val Violet  = Color(0xFF6C63FF)
private val RedDeny = Color(0xFFFF4D6D)
private val TextSec = Color(0x44FFFFFF)
private val TextMid = Color(0x88FFFFFF)
private val Border  = Color(0x1AFFFFFF)
private val Surface = Color(0x12FFFFFF)

@SuppressLint("QueryPermissionsNeeded")
@Composable
fun DenyListContent() {
    val context = LocalContext.current

    var appList    by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var loading    by remember { mutableStateOf(true) }
    var search     by remember { mutableStateOf("") }
    var denyList   by remember { mutableStateOf<Set<String>>(emptySet()) }

    val filtered = remember(appList, search) {
        val q = search.lowercase().trim()
        if (q.isEmpty()) appList
        else appList.filter { it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
    }

    LaunchedEffect(Unit) {
        denyList = SharedPrefs.getDenyList()
        withContext(Dispatchers.IO) {
            try {
                val pm = context.packageManager
                val pkgs = pm.getInstalledApplications(0)
                val apps = mutableListOf<AppInfo>()
                for (pkg in pkgs) {
                    val isSys = (pkg.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    if (isSys && !denyList.contains(pkg.packageName)) continue
                    val name = pm.getApplicationLabel(pkg).toString()
                    val icon = try { pm.getApplicationIcon(pkg) } catch (e: Exception) { null }
                    apps.add(AppInfo(pkg.packageName, name, icon, isSys))
                }
                apps.sortBy { it.appName.lowercase() }
                withContext(Dispatchers.Main) {
                    appList = apps
                    loading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { loading = false }
            }
        }
    }

    fun toggle(app: AppInfo) {
        val current = denyList.toMutableSet()
        if (current.contains(app.packageName)) {
            current.remove(app.packageName)
            SharedPrefs.removeFromDenyList(app.packageName)
        } else {
            current.add(app.packageName)
            SharedPrefs.addToDenyList(app.packageName)
        }
        denyList = current
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Header
        Column(modifier = Modifier.padding(horizontal = 20.dp, top = 16.dp, bottom = 10.dp)) {
            Text("Deny List", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0x1AFFFFFF))
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("🔍", fontSize = 12.sp, color = TextMid)
                BasicTextField(
                    value = search,
                    onValueChange = { search = it },
                    modifier = Modifier.weight(1f),
                    textStyle = androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp),
                    singleLine = true,
                    decorationBox = { inner ->
                        if (search.isEmpty()) Text("Search...", color = TextSec, fontSize = 12.sp)
                        inner()
                    },
                    cursorBrush = SolidColor(Violet)
                )
            }
        }

        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Violet)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    val isDenied = denyList.contains(app.packageName)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (isDenied) Color(0x1AFF4D6D) else Surface)
                            .border(1.dp, if (isDenied) RedDeny.copy(0.2f) else Color.Transparent, RoundedCornerShape(16.dp))
                            .clickable { toggle(app) }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        AppIconCircle(app = app, size = 40.dp, cornerRadius = 12.dp)
                        Column(Modifier.weight(1f)) {
                            Text(app.appName, color = if (isDenied) RedDeny else Color.White,
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(app.packageName, color = TextSec, fontSize = 9.sp,
                                fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        // Toggle circle
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (isDenied) RedDeny else Color(0x1AFFFFFF))
                                .border(if (!isDenied) 1.5.dp else 0.dp, Color(0x33FFFFFF), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            AnimatedVisibility(
                                visible = isDenied,
                                enter = scaleIn(spring(Spring.DampingRatioMediumBouncy)),
                                exit = scaleOut()
                            ) {
                                Text("✔", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}
