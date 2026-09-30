package de.kf.blitztext

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

val WarmColors = lightColorScheme(
    primary = Color(0xFF9B4E32), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF3C1608),
    secondary = Color(0xFF646E51), secondaryContainer = Color(0xFFE2E8D4),
    background = Color(0xFFFFF8F2), surface = Color(0xFFFFF8F2),
    surfaceVariant = Color(0xFFF1E5DC), onSurface = Color(0xFF30251F),
    onSurfaceVariant = Color(0xFF75665D)
)
private fun number(value: Long) = String.format(Locale.GERMANY, "%,d", value)
private fun latency(value: Double?) = value?.let { String.format(Locale.GERMANY, "%.2f s", it / 1000) } ?: "–"
private fun realtime(rows: List<DictationStat>) = sttRealtimeFactor(rows)?.let { String.format(Locale.GERMANY, "%.1f× Echtzeit", it) } ?: "–× Echtzeit"
private fun duration(ms: Long): String = if (ms < 60_000) "${ms / 1000} s" else "${ms / 60_000} min ${ms / 1000 % 60} s"

@Composable
fun Dashboard(stats: UsageStats, refresh: Int, modifier: Modifier = Modifier) {
    var period by remember { mutableStateOf(StatsPeriod.TODAY) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val revision by stats.revision.collectAsState()
    val storageError by stats.storageError.collectAsState()
    var summary by remember { mutableStateOf<UsageSummary?>(null) }
    var readError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    var appNames by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(Unit) { while (true) { today = LocalDate.now(); delay(30_000) } }
    LaunchedEffect(period, revision, refresh, today) {
        today = LocalDate.now()
        summary = null
        runCatching { stats.read(period, today) }.onSuccess { data ->
            summary = data; readError = false
            appNames = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                data.successful.mapNotNull { it.targetPackage }.distinct().associateWith { pkg ->
                    runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
            }
        }.onFailure { readError = true }
    }
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Dein TapStop", style = MaterialTheme.typography.headlineLarge)
        Text("Gesprochen. Erledigt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatsPeriod.entries.forEach { item ->
                FilterChip(selected = period == item, onClick = { period = item }, label = { Text(item.label) })
            }
        }
        if (storageError || readError) Text("Statistiken konnten nicht gespeichert oder geladen werden.", color = MaterialTheme.colorScheme.error)
        val data = summary
        if (data == null) {
            if (!readError) CircularProgressIndicator()
        } else {
            BoxWithConstraints {
                if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Wörter", number(data.words), Modifier.weight(1f), true)
                        Metric("Diktate", number(data.successful.size.toLong()), Modifier.weight(1f))
                        Metric("Sprechzeit", duration(data.recordingMs), Modifier.weight(1f))
                        Metric("Ø WPM", String.format(Locale.GERMANY, "%.0f", data.wpm), Modifier.weight(1f))
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Wörter", number(data.words), Modifier.weight(1f), true)
                        Metric("Diktate", number(data.successful.size.toLong()), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric("Sprechzeit", duration(data.recordingMs), Modifier.weight(1f))
                        Metric("Ø WPM", String.format(Locale.GERMANY, "%.0f", data.wpm), Modifier.weight(1f))
                    }
                }
            }
            Text("Wörter aus dem Rohtranskript · WPM über die gesamte Aufnahmezeit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.successful.isEmpty()) Text("Dein nächstes Diktat macht den Anfang.", style = MaterialTheme.typography.bodyMedium)
            val errors = data.attempts.count { !it.success }
            if (errors > 0) Text("$errors fehlgeschlagene Verarbeitung(en) · nicht in den Nutzungszahlen enthalten", style = MaterialTheme.typography.bodySmall)
            Section("Aktivität") { ActivityCalendar(data, period, today) }
            Section("Provider") {
                if (data.successful.isEmpty()) Text("Noch keine Nutzung")
                data.successful.groupBy { it.provider }.forEach { (provider, rows) ->
                    Text(provider, style = MaterialTheme.typography.titleMedium)
                    Text("${rows.size} Diktate · ${number(rows.sumOf { (it.rawWords ?: 0).toLong() })} Wörter")
                    Text("STT-Zeit ${latency(median(rows.mapNotNull { it.sttMs }))} · ${realtime(rows)}", color = MaterialTheme.colorScheme.primary)
                    rows.groupBy { it.model }.forEach { (model, modelRows) ->
                        Text("$provider · $model", style = MaterialTheme.typography.titleSmall)
                        Text("${modelRows.size} Diktate · ${number(modelRows.sumOf { (it.rawWords ?: 0).toLong() })} Wörter", style = MaterialTheme.typography.bodySmall)
                        Text("STT-Zeit ${latency(median(modelRows.mapNotNull { it.sttMs }))} · ${realtime(modelRows)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("STT-Zeit: Median. × Echtzeit: gesamte Aufnahmezeit / gesamte STT-Zeit. Nur erfolgreiche Diktate mit messbaren Zeiten.", style = MaterialTheme.typography.bodySmall)
            }
            Section("Verarbeitungszeit") {
                StatLine("STT-Zeit · Median", latency(median(data.successful.mapNotNull { it.sttMs })))
                StatLine("Gesamt · Median", latency(median(data.successful.mapNotNull { it.totalMs })))
                StatLine("Rewrite · Median", latency(median(data.successful.mapNotNull { it.rewriteMs })))
                Text("Gesamt: Aufnahmeende bis fertiger Text. Nur erfolgreich verarbeitete Diktate.", style = MaterialTheme.typography.bodySmall)
            }
            Section("Apps") {
                val apps = data.successful.groupBy { it.targetPackage }.entries.sortedByDescending { (_, rows) -> rows.sumOf { (it.rawWords ?: 0).toLong() } }.take(5)
                if (apps.isEmpty()) Text("Noch keine Ziel-Apps")
                apps.forEach { (pkg, rows) -> StatLine(pkg?.let { appNames[it] ?: it } ?: "Unbekannt", "${number(rows.sumOf { (it.rawWords ?: 0).toLong() })} Wörter") }
            }
            Section("Modi") {
                val total = data.successful.size
                Mode.entries.forEach { mode ->
                    val rows = data.successful.filter { it.mode == mode.name }
                    val percent = if (total > 0) (rows.size * 100.0 / total).roundToInt() else 0
                    StatLine(mode.label, "${rows.size} · $percent %")
                    StatLine("${mode.label} · Roh → final", "${number(rows.sumOf { (it.rawWords ?: 0).toLong() })} → ${number(rows.sumOf { (it.finalWords ?: 0).toLong() })}")
                }
                StatLine("Rohwörter → finale Wörter", "${number(data.words)} → ${number(data.finalWords)}")
                Text("Final = zur Einfügung oder Zwischenablage bereitgestellter Text.", style = MaterialTheme.typography.bodySmall)
            }
            Text("Nur auf diesem Gerät. Keine Texte, Audiodateien oder Cloud-Statistiken.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier, prominent: Boolean = false) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = if (prominent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text(value, style = MaterialTheme.typography.headlineMedium)
        }
    }
}
@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = .75f))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
@Composable
private fun StatLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}
@Composable
private fun ActivityCalendar(data: UsageSummary, period: StatsPeriod, today: LocalDate) {
    val colors = listOf(MaterialTheme.colorScheme.surfaceVariant, Color(0xFFF1C4A7), Color(0xFFD68F65), Color(0xFF9B4E32))
    fun level(words: Long) = when { words == 0L -> 0; words < 100 -> 1; words < 500 -> 2; else -> 3 }
    @Composable fun cell(day: LocalDate?, modifier: Modifier, showDay: Boolean) {
        val words = day?.let { data.days[it] } ?: 0L
        Box(modifier.clip(RoundedCornerShape(4.dp)).background(if (day == null || day > today) Color.Transparent else colors[level(words)])
            .semantics { contentDescription = day?.let { "$it: $words Wörter" } ?: "" }, contentAlignment = Alignment.Center) {
            if (showDay && day != null) Text(day.dayOfMonth.toString(), style = MaterialTheme.typography.labelSmall,
                color = if (words >= 500 && day <= today) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
    if (period == StatsPeriod.TODAY) {
        Text(today.format(DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN)), style = MaterialTheme.typography.bodySmall)
        cell(today, Modifier.size(36.dp), true)
    } else if (period != StatsPeriod.ALL) {
        val first = if (period == StatsPeriod.MONTH) today.withDayOfMonth(1) else StatsPeriod.WEEK.start(today)!!
        val days = if (period == StatsPeriod.MONTH) first.lengthOfMonth() else 7
        val offset = first.dayOfWeek.value - 1
        Text(if (period == StatsPeriod.MONTH) today.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)) else "Diese Woche", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
        }
        for (week in 0 until (offset + days + 6) / 7) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                for (weekday in 0..6) {
                    val index = week * 7 + weekday - offset
                    cell(if (index in 0 until days) first.plusDays(index.toLong()) else null, Modifier.weight(1f).height(30.dp), true)
                }
            }
        }
    } else {
        // Totals cover all history; this compact heatmap deliberately shows the latest year.
        Text("Letzte 53 Wochen · links älter, rechts heute", style = MaterialTheme.typography.bodySmall)
        val first = StatsPeriod.WEEK.start(today)!!.minusWeeks(52)
        val scroll = rememberScrollState()
        LaunchedEffect(Unit) { scroll.scrollTo(scroll.maxValue) }
        Row(Modifier.horizontalScroll(scroll), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (week in 0..52) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (weekday in 0..6) cell(first.plusDays((week * 7 + weekday).toLong()), Modifier.size(13.dp), false)
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        colors.forEach { Box(Modifier.size(12.dp).background(it, RoundedCornerShape(3.dp))) }
        Text("0 · 1–99 · 100–499 · 500+ Wörter", style = MaterialTheme.typography.labelSmall)
    }
}
