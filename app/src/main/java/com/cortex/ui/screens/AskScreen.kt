package com.cortex.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortex.data.AppDatabase
import com.cortex.data.NodeEntity
import com.cortex.pipeline.QueryService
import com.cortex.ui.components.DomainChip
import com.cortex.ui.components.DomainDot
import com.cortex.ui.theme.InkMist
import com.cortex.ui.theme.glassSurface

@Composable
fun AskScreen(
    onOpenNode: (String) -> Unit,
    initialQuestion: String? = null,
    onConsumedInitialQuestion: () -> Unit = {}
) {
    val ctx = LocalContext.current
    val vm: AskViewModel = viewModel(factory = AskViewModel.Factory(ctx.applicationContext as android.app.Application))
    val state by vm.state.collectAsState()
    var input by remember { mutableStateOf("") }

    // Fire any incoming question exactly once (Amendment T-A2).
    LaunchedEffect(initialQuestion) {
        val q = initialQuestion?.trim().orEmpty()
        if (q.isNotEmpty()) {
            vm.ask(q)
            onConsumedInitialQuestion()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(4.dp))
        Text(
            "Ask Cortex",
            style = MaterialTheme.typography.displayMedium,
            color = InkMist.PrimaryText
        )
        Text(
            "Questions are scoped automatically — work, personal, or both.",
            style = MaterialTheme.typography.bodyMedium,
            color = InkMist.SecondaryText
        )
        Spacer(Modifier.height(20.dp))

        when (val s = state) {
            is AskState.Idle -> AskEmptyHint()
            is AskState.Thinking -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = InkMist.Moonstone, strokeWidth = 2.dp)
                    Spacer(Modifier.height(12.dp))
                    Text("Thinking…", color = InkMist.SecondaryText, style = MaterialTheme.typography.bodyMedium)
                }
            }
            is AskState.Answered -> AnswerView(s, onOpenNode, modifier = Modifier.weight(1f))
            is AskState.Failed -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(s.message, color = InkMist.SecondaryText, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 28)
                .padding(start = 8.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("What would you like to know?", color = InkMist.SecondaryText) },
                modifier = Modifier.weight(1f),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color.Transparent,
                    unfocusedBorderColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    cursorColor = InkMist.Moonstone,
                    focusedTextColor = InkMist.PrimaryText,
                    unfocusedTextColor = InkMist.PrimaryText
                )
            )
            IconButton(
                onClick = {
                    if (input.isNotBlank()) {
                        vm.ask(input.trim())
                        input = ""
                    }
                },
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(InkMist.Moonstone),
                enabled = state !is AskState.Thinking
            ) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Ask", tint = InkMist.CanvasTop)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun AskEmptyHint() {
    Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "Try:",
                color = InkMist.SecondaryText,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(Modifier.height(8.dp))
            listOf(
                "Whose birthdays are coming up?",
                "What's open on Project X?",
                "I'm meeting Cam — what should I keep in mind?"
            ).forEach { suggestion ->
                Text(
                    "“$suggestion”",
                    color = InkMist.PrimaryText.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun AnswerView(state: AskState.Answered, onOpenNode: (String) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DomainChip(state.answer.classification.domain)
            Spacer(Modifier.width(8.dp))
            Text(
                state.answer.classification.intent.replaceFirstChar { it.uppercase() },
                color = InkMist.SecondaryText,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .glassSurface(cornerRadius = 18)
                .padding(16.dp)
        ) {
            Text(
                text = "“${state.question}”",
                color = InkMist.SecondaryText,
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
        Spacer(Modifier.height(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                state.answer.text,
                color = InkMist.PrimaryText,
                style = MaterialTheme.typography.bodyLarge
            )
        }
        if (state.answer.sources.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Sources", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.heightIn(max = 180.dp)) {
                items(state.answer.sources, key = { it.id }) { node ->
                    SourceChip(node, onOpenNode)
                }
            }
        }
    }
}

@Composable
private fun SourceChip(node: NodeEntity, onClick: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkMist.SoftFill)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        DomainDot(node.domain)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(node.name, color = InkMist.PrimaryText, style = MaterialTheme.typography.titleMedium)
            if (node.summary.isNotBlank()) {
                Text(
                    node.summary.lines().firstOrNull() ?: "",
                    color = InkMist.SecondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        Text(
            node.type,
            color = InkMist.SecondaryText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(InkMist.SoftFillStrong)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        IconButton(onClick = { onClick(node.id) }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Open", tint = InkMist.Moonstone)
        }
    }
}
