package com.cortex.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortex.data.AppDatabase
import com.cortex.data.NodeEntity
import com.cortex.ui.components.DomainDot
import com.cortex.ui.theme.InkMist
import com.cortex.ui.theme.glassSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BrowseViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getDatabase(getApplication()).cortexDao()
    val query = MutableStateFlow("")

    val nodes: StateFlow<List<NodeEntity>> = combine(dao.getRootNodesFlow(), query) { roots, q ->
        if (q.isBlank()) roots
        else dao.searchNodesScoped(q, domain = null, limit = 50)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = BrowseViewModel(app) as T
    }
}

@Composable
fun BrowseScreen(onOpenNode: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm: BrowseViewModel = viewModel(factory = BrowseViewModel.Factory(ctx.applicationContext as Application))
    val nodes by vm.nodes.collectAsState()
    val query by vm.query.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = InkMist.SecondaryText) },
            placeholder = { Text("Search the map", color = InkMist.SecondaryText) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(cornerRadius = 16),
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
        Spacer(Modifier.height(20.dp))
        if (nodes.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (query.isBlank()) "Capture your first thought to see it appear here."
                    else "Nothing matches “$query” yet.",
                    color = InkMist.SecondaryText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(nodes, key = { it.id }) { node ->
                    NodeRow(node, onClick = { onOpenNode(node.id) })
                }
            }
        }
    }
}

@Composable
fun NodeRow(node: NodeEntity, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkMist.SoftFill)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        DomainDot(node.domain)
        Spacer(Modifier.width(12.dp))
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
        Spacer(Modifier.width(8.dp))
        Text(
            node.type,
            color = InkMist.SecondaryText,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(InkMist.SoftFillStrong)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}
