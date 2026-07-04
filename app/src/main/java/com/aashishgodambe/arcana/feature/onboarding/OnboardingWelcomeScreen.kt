package com.aashishgodambe.arcana.feature.onboarding

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aashishgodambe.arcana.ui.theme.ArcanaTheme

private val CSV_MIME_TYPES = arrayOf(
    "text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel",
)

@Composable
fun OnboardingWelcomeScreen(
    onImportPicked: (Uri) -> Unit,
    onStartFresh: () -> Unit,
) {
    val c = ArcanaTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportPicked(uri)
    }
    Scaffold(containerColor = c.bg) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(horizontal = 22.dp)) {
            Column(Modifier.padding(top = 60.dp, bottom = 18.dp)) {
                Box(
                    Modifier.size(54.dp).clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(c.iris, c.gold))),
                    contentAlignment = Alignment.Center,
                ) { Text("✦", color = Color.White, fontSize = 26.sp) }
                Spacer(Modifier.height(22.dp))
                Text(
                    "Your collection's arcana, on your device.",
                    style = MaterialTheme.typography.headlineLarge,
                    color = c.text,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Track value, identify new finds, and ask questions about what you own — privately.",
                    color = c.textDim, fontSize = 15.sp, modifier = Modifier.width(280.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Column(Modifier.padding(bottom = 30.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PathCard("＋", "Start fresh", "Add items by camera as you go.", null, onStartFresh)
                PathCard(
                    "⇪", "Import from HobbyDB", "Bring in an existing collection from a CSV export.",
                    "Requires HobbyDB Premium export",
                ) { picker.launch(CSV_MIME_TYPES) }
            }
        }
    }
}

@Composable
private fun PathCard(icon: String, title: String, desc: String, req: String?, onClick: () -> Unit) {
    val c = ArcanaTheme.colors
    Row(
        Modifier.clip(RoundedCornerShape(18.dp)).background(c.surface)
            .border(1.dp, c.hairlineStrong, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(13.dp)).background(c.irisSoft),
            contentAlignment = Alignment.Center,
        ) { Text(icon, color = c.iris, fontSize = 20.sp) }
        Column(Modifier.weight(1f)) {
            Text(title, color = c.text, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
            Text(desc, color = c.textDim, fontSize = 12.5f.sp)
            req?.let { Text(it, color = c.textFaint, fontSize = 10.sp) }
        }
        Text("›", color = c.textFaint, fontSize = 18.sp)
    }
}
