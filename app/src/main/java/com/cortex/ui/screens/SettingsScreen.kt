package com.cortex.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.cortex.BuildConfig
import com.cortex.pipeline.EmbeddingService
import com.cortex.ui.theme.InkMist

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val keyConfigured = BuildConfig.DEEPSEEK_API_KEY.isNotBlank()
    val embedder = EmbeddingService.get(ctx.applicationContext)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        Text("Settings", style = MaterialTheme.typography.displayMedium, color = InkMist.PrimaryText)

        SettingRow(
            label = "DeepSeek API key",
            value = if (keyConfigured) "configured (local.properties)" else "missing",
            valueColor = if (keyConfigured) InkMist.Moonstone else InkMist.DomainPersonal
        )
        SettingRow(
            label = "Embedding model",
            value = embedder.modelVersion,
            valueColor = if (embedder.modelVersion.startsWith("minilm")) InkMist.Moonstone else InkMist.DomainPersonal
        )
        SettingRow(
            label = "Database",
            value = "cortex_database (local, SQLite)",
            valueColor = InkMist.PrimaryText
        )
        SettingRow(
            label = "Backup / Restore",
            value = "coming soon",
            valueColor = InkMist.SecondaryText
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, valueColor: androidx.compose.ui.graphics.Color) {
    Column {
        Text(label, color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, style = MaterialTheme.typography.bodyLarge)
    }
}
