package com.cortex.ui.screens

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cortex.data.AppDatabase
import com.cortex.reminders.ReminderScheduler
import com.cortex.ui.components.DomainDot
import com.cortex.ui.theme.InkMist
import com.cortex.ui.theme.domainColor
import com.cortex.ui.theme.glassSurface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

data class DayMark(val count: Int, val domains: List<String?>)

data class DayEntry(
    val id: String,
    val time: LocalDateTime,
    val title: String,
    val isReminder: Boolean,
    val domain: String?,
    val done: Boolean,
    val nodeId: String?
)

class ReminderCalendarViewModel(app: Application) : AndroidViewModel(app) {
    private val dao = AppDatabase.getDatabase(getApplication()).cortexDao()
    private val scheduler = ReminderScheduler(getApplication())
    private val zone: ZoneId = ZoneId.systemDefault()

    private val _month = MutableStateFlow(YearMonth.now())
    val month: StateFlow<YearMonth> = _month.asStateFlow()

    private val _selected = MutableStateFlow(LocalDate.now())
    val selected: StateFlow<LocalDate> = _selected.asStateFlow()

    private val _marks = MutableStateFlow<Map<LocalDate, DayMark>>(emptyMap())
    val marks: StateFlow<Map<LocalDate, DayMark>> = _marks.asStateFlow()

    private val _dayEntries = MutableStateFlow<List<DayEntry>>(emptyList())
    val dayEntries: StateFlow<List<DayEntry>> = _dayEntries.asStateFlow()

    init { refreshMonth(); refreshDay() }

    fun selectDate(d: LocalDate) { _selected.value = d; refreshDay() }
    fun prevMonth() { _month.value = _month.value.minusMonths(1); refreshMonth() }
    fun nextMonth() { _month.value = _month.value.plusMonths(1); refreshMonth() }

    private fun dayRange(d: LocalDate): Pair<Long, Long> {
        val start = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val end = d.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        return start to end
    }

    private fun refreshMonth() = viewModelScope.launch {
        val m = _month.value
        val start = m.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val end = m.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val byDay = HashMap<LocalDate, MutableList<String?>>()
        dao.getRemindersBetween(start, end)
            .filter { it.status == "scheduled" || it.status == "fired" }
            .forEach { r ->
                val d = Instant.ofEpochMilli(r.triggerAt).atZone(zone).toLocalDate()
                byDay.getOrPut(d) { mutableListOf() }.add(null)
            }
        dao.getItemsWithDueBetween(start, end)
            .filter { it.remindAt == null } // reminders already counted above
            .forEach { item ->
                val d = Instant.ofEpochMilli(item.dueAt!!).atZone(zone).toLocalDate()
                byDay.getOrPut(d) { mutableListOf() }.add(item.domain)
            }
        _marks.value = byDay.mapValues { (_, list) -> DayMark(list.size, list.distinct().take(3)) }
    }

    private fun refreshDay() = viewModelScope.launch {
        val (start, end) = dayRange(_selected.value)
        val entries = mutableListOf<DayEntry>()
        dao.getRemindersBetween(start, end).forEach { r ->
            entries.add(
                DayEntry(
                    id = "rem:${r.id}",
                    time = Instant.ofEpochMilli(r.triggerAt).atZone(zone).toLocalDateTime(),
                    title = r.title,
                    isReminder = true,
                    domain = null,
                    done = r.status == "done" || r.status == "dismissed",
                    nodeId = r.relatedNodeId
                )
            )
        }
        dao.getItemsWithDueBetween(start, end).filter { it.remindAt == null }.forEach { item ->
            entries.add(
                DayEntry(
                    id = "item:${item.id}",
                    time = Instant.ofEpochMilli(item.dueAt!!).atZone(zone).toLocalDateTime(),
                    title = item.content,
                    isReminder = false,
                    domain = item.domain,
                    done = item.status == "done",
                    nodeId = item.nodeId
                )
            )
        }
        _dayEntries.value = entries.sortedBy { it.time }
    }

    fun complete(entry: DayEntry) = viewModelScope.launch {
        val rawId = entry.id.substringAfter(":")
        if (entry.isReminder) {
            dao.markReminderStatus(rawId, "done", System.currentTimeMillis())
            scheduler.cancel(rawId)
        } else {
            dao.updateItemStatus(rawId, "done")
        }
        refreshDay(); refreshMonth()
    }

    fun snooze(entry: DayEntry) = viewModelScope.launch {
        if (!entry.isReminder) return@launch
        val rawId = entry.id.substringAfter(":")
        val next = System.currentTimeMillis() + 10 * 60 * 1000
        dao.updateReminderTrigger(rawId, next)
        dao.getReminderById(rawId)?.let { scheduler.schedule(it) }
        refreshDay(); refreshMonth()
    }

    class Factory(private val app: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ReminderCalendarViewModel(app) as T
    }
}

@Composable
fun ReminderCalendarScreen(onOpenNode: (String) -> Unit) {
    val ctx = LocalContext.current
    val vm: ReminderCalendarViewModel = viewModel(factory = ReminderCalendarViewModel.Factory(ctx.applicationContext as Application))
    val month by vm.month.collectAsState()
    val selected by vm.selected.collectAsState()
    val marks by vm.marks.collectAsState()
    val entries by vm.dayEntries.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        MonthHeader(month, onPrev = vm::prevMonth, onNext = vm::nextMonth)
        Spacer(Modifier.height(12.dp))
        MonthGrid(month, selected, marks, onSelect = vm::selectDate)
        Spacer(Modifier.height(16.dp))
        Text(
            selected.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")),
            style = MaterialTheme.typography.titleMedium,
            color = InkMist.PrimaryText
        )
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Text("Nothing scheduled for this day.", color = InkMist.SecondaryText, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                items(entries, key = { it.id }) { e ->
                    EntryRow(e, onComplete = { vm.complete(e) }, onSnooze = { vm.snooze(e) }, onOpen = { e.nodeId?.let(onOpenNode) })
                }
            }
        }
    }
}

@Composable
private fun MonthHeader(month: YearMonth, onPrev: () -> Unit, onNext: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        IconButton(onClick = onPrev) { Icon(Icons.Rounded.ChevronLeft, "Previous month", tint = InkMist.PrimaryText) }
        Text(
            month.format(DateTimeFormatter.ofPattern("MMMM yyyy")),
            style = MaterialTheme.typography.titleLarge,
            color = InkMist.PrimaryText,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onNext) { Icon(Icons.Rounded.ChevronRight, "Next month", tint = InkMist.PrimaryText) }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    selected: LocalDate,
    marks: Map<LocalDate, DayMark>,
    onSelect: (LocalDate) -> Unit
) {
    val today = LocalDate.now()
    val firstDow = month.atDay(1).dayOfWeek.value // Mon=1..Sun=7
    val leading = firstDow - 1
    val daysInMonth = month.lengthOfMonth()
    val cells = leading + daysInMonth
    val rows = (cells + 6) / 7

    Column(Modifier.fillMaxWidth().glassSurface(cornerRadius = 20).padding(10.dp)) {
        Row(Modifier.fillMaxWidth()) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { d ->
                Text(d, color = InkMist.SecondaryText, style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(6.dp))
        var dayNum = 1
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth()) {
                for (c in 0 until 7) {
                    val index = r * 7 + c
                    if (index < leading || dayNum > daysInMonth) {
                        Box(Modifier.weight(1f).aspectRatio(1f))
                    } else {
                        val date = month.atDay(dayNum)
                        DayCell(
                            date = date,
                            isToday = date == today,
                            isSelected = date == selected,
                            mark = marks[date],
                            modifier = Modifier.weight(1f),
                            onClick = { onSelect(date) }
                        )
                        dayNum++
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    date: LocalDate,
    isToday: Boolean,
    isSelected: Boolean,
    mark: DayMark?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = when {
        isSelected -> InkMist.Moonstone
        isToday -> InkMist.Moonstone.copy(alpha = 0.14f)
        else -> Color.Transparent
    }
    val fg = if (isSelected) Color.White else InkMist.PrimaryText
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(3.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                date.dayOfMonth.toString(),
                color = fg,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal
            )
            if (mark != null && mark.count > 0) {
                Spacer(Modifier.height(2.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    mark.domains.take(3).forEach { dom ->
                        Box(
                            Modifier.size(5.dp).clip(CircleShape)
                                .background(if (isSelected) Color.White else domainColor(dom))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryRow(e: DayEntry, onComplete: () -> Unit, onSnooze: () -> Unit, onOpen: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(InkMist.SoftFill)
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Text(
            e.time.format(DateTimeFormatter.ofPattern("HH:mm")),
            color = InkMist.Moonstone,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(46.dp)
        )
        DomainDot(e.domain)
        Spacer(Modifier.width(10.dp))
        Text(
            e.title,
            color = if (e.done) InkMist.SecondaryText else InkMist.PrimaryText,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (!e.done) {
            if (e.isReminder) {
                IconButton(onClick = onSnooze) { Icon(Icons.Rounded.Snooze, "Snooze", tint = InkMist.SecondaryText) }
            }
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onComplete()
            }) { Icon(Icons.Rounded.Check, "Complete", tint = InkMist.Moonstone) }
        }
    }
}
