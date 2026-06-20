package com.cortex.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortex.data.AppDatabase
import com.cortex.data.EdgeEntity
import com.cortex.data.ItemEntity
import com.cortex.data.NodeEntity
import com.cortex.ui.components.DomainChip
import com.cortex.ui.components.DomainDot
import com.cortex.ui.components.Hairline
import com.cortex.ui.theme.InkMist
import com.cortex.ui.theme.glassSurface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class NodeDetail(
    val node: NodeEntity,
    val children: List<NodeEntity>,
    val items: List<ItemEntity>,
    val links: List<Pair<EdgeEntity, NodeEntity>>
)

class NodeDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getDatabase(getApplication()).cortexDao()
    private val embeddings = com.cortex.pipeline.EmbeddingService.get(getApplication())
    private val _state = MutableStateFlow<NodeDetail?>(null)
    val state: StateFlow<NodeDetail?> = _state.asStateFlow()
    private var currentId: String? = null

    fun load(nodeId: String) {
        currentId = nodeId
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { loadDetail(nodeId) }
        }
    }

    private suspend fun loadDetail(nodeId: String): NodeDetail? {
        val node = dao.getNodeById(nodeId) ?: return null
        val children = dao.getChildren(nodeId)
        val items = dao.getItemsForNode(nodeId, null)
        val edges = dao.getEdgesForNode(nodeId, null)
        val otherIds = edges.map { if (it.sourceNodeId == nodeId) it.targetNodeId else it.sourceNodeId }.distinct()
        val others = dao.getNodesByIds(otherIds).associateBy { it.id }
        val links = edges.mapNotNull { e ->
            val otherId = if (e.sourceNodeId == nodeId) e.targetNodeId else e.sourceNodeId
            val other = others[otherId] ?: return@mapNotNull null
            e to other
        }
        return NodeDetail(node, children, items, links)
    }

    private fun reload() {
        val id = currentId ?: return
        viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { loadDetail(id) }
        }
    }

    fun editNode(name: String, summary: String, domain: String?) {
        val id = currentId ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.updateNode(id, name, summary, domain, System.currentTimeMillis())
                // Re-embed so future fuzzy retrieval still hits this node correctly.
                val vec = embeddings.embed("$name. $summary")
                dao.insertEmbedding(
                    com.cortex.data.EmbeddingEntity(
                        ownerType = "node",
                        ownerId = id,
                        vector = com.cortex.pipeline.Vectors.toBlob(vec),
                        modelVersion = embeddings.modelVersion
                    )
                )
            }
            reload()
        }
    }

    fun editItem(itemId: String, content: String, status: String?) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.updateItem(itemId, content, status)
                val vec = embeddings.embed(content)
                dao.insertEmbedding(
                    com.cortex.data.EmbeddingEntity(
                        ownerType = "item",
                        ownerId = itemId,
                        vector = com.cortex.pipeline.Vectors.toBlob(vec),
                        modelVersion = embeddings.modelVersion
                    )
                )
            }
            reload()
        }
    }

    fun deleteItem(itemId: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                dao.deleteItem(itemId)
                dao.deleteEmbedding("item", itemId)
            }
            reload()
        }
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = NodeDetailViewModel(app) as T
    }
}

@Composable
fun NodeDetailScreen(nodeId: String, onOpenNode: (String) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val vm: NodeDetailViewModel = viewModel(
        factory = NodeDetailViewModel.Factory(ctx.applicationContext as Application),
        key = nodeId
    )
    LaunchedEffect(nodeId) { vm.load(nodeId) }
    val detail by vm.state.collectAsState()

    // Editing state — null when no dialog is open.
    var editingNode by remember { mutableStateOf(false) }
    var editingItem by remember { mutableStateOf<ItemEntity?>(null) }
    var deletingItem by remember { mutableStateOf<ItemEntity?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = InkMist.PrimaryText)
        }

        val d = detail
        if (d == null) {
            Text("Loading…", color = InkMist.SecondaryText)
            return
        }

        // Node header — tappable to edit name/summary/domain.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                d.node.name,
                style = MaterialTheme.typography.displayMedium,
                color = InkMist.PrimaryText,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            DomainChip(d.node.domain)
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = { editingNode = true }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit node", tint = InkMist.Moonstone)
            }
        }
        Text(d.node.type, color = InkMist.SecondaryText, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(12.dp))
        if (d.node.summary.isNotBlank()) {
            Text(
                d.node.summary,
                color = InkMist.PrimaryText.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodyLarge
            )
        }
        Spacer(Modifier.height(24.dp))

        if (d.items.isNotEmpty()) {
            Section("Items") {
                d.items.forEach { item ->
                    ItemRow(
                        item = item,
                        onEdit = { editingItem = item },
                        onDelete = { deletingItem = item }
                    )
                }
            }
        }

        if (d.children.isNotEmpty()) {
            Section("Sub-nodes") {
                d.children.forEach { c -> NodeRow(c) { onOpenNode(c.id) } }
            }
        }

        if (d.links.isNotEmpty()) {
            Section("Linked") {
                d.links.forEach { (edge, other) -> LinkRow(edge, other) { onOpenNode(other.id) } }
            }
        }

        Spacer(Modifier.height(40.dp))
    }

    if (editingNode && detail != null) {
        EditNodeDialog(
            node = detail!!.node,
            onDismiss = { editingNode = false },
            onSave = { name, summary, domain ->
                vm.editNode(name, summary, domain)
                editingNode = false
            }
        )
    }
    editingItem?.let { item ->
        EditItemDialog(
            item = item,
            onDismiss = { editingItem = null },
            onSave = { content, status ->
                vm.editItem(item.id, content, status)
                editingItem = null
            }
        )
    }
    deletingItem?.let { item ->
        ConfirmDeleteDialog(
            preview = item.content,
            onDismiss = { deletingItem = null },
            onConfirm = {
                vm.deleteItem(item.id)
                deletingItem = null
            }
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(title, color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
    Spacer(Modifier.height(8.dp))
    Hairline()
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ItemRow(
    item: ItemEntity,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .glassSurface(cornerRadius = 14)
            .padding(start = 14.dp, top = 12.dp, end = 6.dp, bottom = 12.dp)
    ) {
        DomainDot(item.domain, Modifier.padding(top = 6.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.content, color = InkMist.PrimaryText, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(
                    item.kind,
                    color = InkMist.SecondaryText,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(InkMist.SoftFillStrong)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
                item.status?.let {
                    Spacer(Modifier.width(8.dp))
                    Text("· $it", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Column {
            IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Edit, contentDescription = "Edit", tint = InkMist.Moonstone, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = InkMist.SecondaryText, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun EditItemDialog(
    item: ItemEntity,
    onDismiss: () -> Unit,
    onSave: (content: String, status: String?) -> Unit
) {
    var content by remember(item.id) { mutableStateOf(item.content) }
    var done by remember(item.id) { mutableStateOf(item.status == "done") }
    val isTask = item.kind == "task"

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val cleaned = content.trim()
                    if (cleaned.isNotEmpty()) {
                        val newStatus = when {
                            !isTask -> item.status
                            done -> "done"
                            else -> "open"
                        }
                        onSave(cleaned, newStatus)
                    }
                },
                enabled = content.isNotBlank()
            ) {
                Text("Save", color = InkMist.Moonstone, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
            }
        },
        title = { Text("Edit item", color = InkMist.PrimaryText, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = InkMist.PrimaryText),
                    shape = RoundedCornerShape(14.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = InkMist.Moonstone,
                        unfocusedBorderColor = InkMist.HairlineGlass,
                        focusedContainerColor = InkMist.GlassFill,
                        unfocusedContainerColor = InkMist.GlassFill,
                        cursorColor = InkMist.Moonstone,
                        focusedTextColor = InkMist.PrimaryText,
                        unfocusedTextColor = InkMist.PrimaryText
                    )
                )
                if (isTask) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = done,
                            onCheckedChange = { done = it },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = InkMist.Moonstone,
                                uncheckedColor = InkMist.SecondaryText
                            )
                        )
                        Text("Mark as done", color = InkMist.PrimaryText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        containerColor = InkMist.CanvasTop,
        textContentColor = InkMist.PrimaryText
    )
}

@Composable
private fun EditNodeDialog(
    node: NodeEntity,
    onDismiss: () -> Unit,
    onSave: (name: String, summary: String, domain: String?) -> Unit
) {
    var name by remember(node.id) { mutableStateOf(node.name) }
    var summary by remember(node.id) { mutableStateOf(node.summary) }
    var domain by remember(node.id) { mutableStateOf(node.domain) }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    val n = name.trim()
                    if (n.isNotEmpty()) onSave(n, summary.trim(), domain)
                },
                enabled = name.isNotBlank()
            ) {
                Text("Save", color = InkMist.Moonstone, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
            }
        },
        title = { Text("Edit node", color = InkMist.PrimaryText, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name", color = InkMist.SecondaryText) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = editFieldColors()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = summary,
                    onValueChange = { summary = it },
                    label = { Text("Summary", color = InkMist.SecondaryText) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = editFieldColors()
                )
                Text("Domain", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DomainChoice("Work", "work", domain) { domain = it }
                    DomainChoice("Personal", "personal", domain) { domain = it }
                    DomainChoice("Unfiled", null, domain) { domain = it }
                }
            }
        },
        containerColor = InkMist.CanvasTop,
        textContentColor = InkMist.PrimaryText
    )
}

@Composable
private fun editFieldColors() = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
    focusedBorderColor = InkMist.Moonstone,
    unfocusedBorderColor = InkMist.HairlineGlass,
    focusedContainerColor = InkMist.GlassFill,
    unfocusedContainerColor = InkMist.GlassFill,
    cursorColor = InkMist.Moonstone,
    focusedTextColor = InkMist.PrimaryText,
    unfocusedTextColor = InkMist.PrimaryText
)

@Composable
private fun DomainChoice(label: String, value: String?, current: String?, onSelect: (String?) -> Unit) {
    val selected = current == value
    val color = com.cortex.ui.theme.domainColor(value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(if (selected) color.copy(alpha = 0.18f) else InkMist.SoftFill)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable { onSelect(value) }
    ) {
        Box(Modifier.size(8.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (selected) color else InkMist.PrimaryText, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ConfirmDeleteDialog(preview: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onConfirm) {
                Text("Delete", color = InkMist.DomainPersonal, style = MaterialTheme.typography.labelLarge)
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = InkMist.SecondaryText, style = MaterialTheme.typography.labelLarge)
            }
        },
        title = { Text("Delete item?", color = InkMist.PrimaryText, style = MaterialTheme.typography.titleLarge) },
        text = {
            Text(
                "\"${preview.take(140)}${if (preview.length > 140) "…" else ""}\"",
                color = InkMist.SecondaryText,
                style = MaterialTheme.typography.bodyMedium
            )
        },
        containerColor = InkMist.CanvasTop,
        textContentColor = InkMist.PrimaryText
    )
}

@Composable
private fun LinkRow(edge: EdgeEntity, other: NodeEntity, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkMist.SoftFill)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(com.cortex.ui.theme.domainColor(edge.domain))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(other.name, color = InkMist.PrimaryText, style = MaterialTheme.typography.titleMedium)
            Text(
                "${edge.relationType.replace('_', ' ')} · ${edge.domain}",
                color = InkMist.SecondaryText,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}
