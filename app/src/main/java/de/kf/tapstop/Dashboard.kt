package de.kf.tapstop

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

val WarmColors = lightColorScheme(
    primary = Color(0xFF9B4E32), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBCC), onPrimaryContainer = Color(0xFF3C1608),
    secondary = Color(0xFF646E51), secondaryContainer = Color(0xFFE2E8D4),
    background = Color(0xFFFFF8F2), surface = Color(0xFFFFF8F2),
    surfaceVariant = Color(0xFFF1E5DC), onSurface = Color(0xFF30251F),
    onSurfaceVariant = Color(0xFF75665D)
)
internal fun Context.statisticsLocale(): Locale {
    val language = getString(R.string.format_language)
    val locales = resources.configuration.locales
    // Android resolves the language resource. Keep its preferred regional variant for formatting.
    return (0 until locales.size()).firstNotNullOfOrNull { locales[it].takeIf { it.language == language } }
        ?: Locale.forLanguageTag(language)
}

internal fun Context.statisticsPlurals(locale: Locale): Resources {
    // Android's plural rules follow the configured locale even when a string falls back to English.
    val config = resources.configuration
    return if (config.locales[0] == locale) resources
    else createConfigurationContext(Configuration(config).apply { setLocale(locale) }).resources
}

@Composable
private fun latency(value: Double?, locale: Locale): String =
    value?.let { stringResource(R.string.duration_seconds, String.format(locale, "%.2f", it / 1000)) }
        ?: stringResource(R.string.dash)

@Composable
private fun realtime(rows: List<DictationStat>, locale: Locale): String {
    val factor = sttRealtimeFactor(rows)?.let { String.format(locale, "%.1f", it) } ?: stringResource(R.string.dash)
    return stringResource(R.string.realtime_factor, factor)
}

@Composable
private fun duration(ms: Long, format: NumberFormat): String {
    val seconds = ms / 1000
    return if (seconds < 60) stringResource(R.string.duration_seconds, format.format(seconds))
    else stringResource(R.string.duration_minutes_seconds, format.format(seconds / 60), format.format(seconds % 60))
}

private fun Resources.wordCount(count: Long, format: NumberFormat): String =
    getQuantityString(R.plurals.word_count, count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), format.format(count))

@Composable
private fun dictationsAndWords(rows: List<DictationStat>, format: NumberFormat, plurals: Resources): String =
    stringResource(R.string.dictations_and_words,
        plurals.getQuantityString(R.plurals.dictation_count, rows.size, format.format(rows.size)),
        plurals.wordCount(rows.sumOf { (it.rawWords ?: 0).toLong() }, format))

@Composable
fun Dashboard(stats: UsageStats, refresh: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val locale = remember(context, configuration) { context.statisticsLocale() }
    val plurals = remember(context, configuration) { context.statisticsPlurals(locale) }
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val percentFormat = remember(locale) { NumberFormat.getPercentInstance(locale) }
    var period by remember { mutableStateOf(StatsPeriod.TODAY) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val revision by stats.revision.collectAsState()
    val storageError by stats.storageError.collectAsState()
    var summary by remember { mutableStateOf<UsageSummary?>(null) }
    var readError by remember { mutableStateOf(false) }
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
        Text(stringResource(R.string.dashboard_title), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.dashboard_tagline), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatsPeriod.entries.forEach { item ->
                FilterChip(selected = period == item, onClick = { period = item }, label = { Text(stringResource(item.labelRes)) })
            }
        }
        if (storageError || readError) Text(stringResource(R.string.stats_load_failed), color = MaterialTheme.colorScheme.error)
        val data = summary
        if (data == null) {
            if (!readError) CircularProgressIndicator()
        } else {
            BoxWithConstraints {
                if (maxWidth >= 600.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric(stringResource(R.string.metric_words), number.format(data.words), Modifier.weight(1f), true)
                        Metric(stringResource(R.string.metric_dictations), number.format(data.successful.size.toLong()), Modifier.weight(1f))
                        Metric(stringResource(R.string.metric_speaking_time), duration(data.recordingMs, number), Modifier.weight(1f))
                        Metric(stringResource(R.string.metric_wpm), String.format(locale, "%.0f", data.wpm), Modifier.weight(1f))
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric(stringResource(R.string.metric_words), number.format(data.words), Modifier.weight(1f), true)
                        Metric(stringResource(R.string.metric_dictations), number.format(data.successful.size.toLong()), Modifier.weight(1f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Metric(stringResource(R.string.metric_speaking_time), duration(data.recordingMs, number), Modifier.weight(1f))
                        Metric(stringResource(R.string.metric_wpm), String.format(locale, "%.0f", data.wpm), Modifier.weight(1f))
                    }
                }
            }
            Text(stringResource(R.string.raw_and_wpm_explanation), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (data.successful.isEmpty()) Text(stringResource(R.string.first_dictation), style = MaterialTheme.typography.bodyMedium)
            val errors = data.attempts.count { !it.success }
            if (errors > 0) Text(plurals.getQuantityString(R.plurals.processing_errors, errors, number.format(errors)), style = MaterialTheme.typography.bodySmall)
            Section(stringResource(R.string.activity)) { ActivityCalendar(data, period, today, locale, number, plurals) }
            Section(stringResource(R.string.providers)) {
                if (data.successful.isEmpty()) Text(stringResource(R.string.no_usage))
                data.successful.groupBy { it.provider }.forEach { (provider, rows) ->
                    Text(if (provider == "qualcomm") "Qualcomm NPU" else provider, style = MaterialTheme.typography.titleMedium)
                    Text(dictationsAndWords(rows, number, plurals))
                    Text(stringResource(R.string.stt_time_and_speed, latency(median(rows.mapNotNull { it.sttMs }), locale), realtime(rows, locale)), color = MaterialTheme.colorScheme.primary)
                    rows.groupBy { it.model }.forEach { (model, modelRows) ->
                        Text(if (provider == "qualcomm") "Qualcomm NPU · Whisper Large V3 Turbo" else "$provider · $model", style = MaterialTheme.typography.titleSmall)
                        Text(dictationsAndWords(modelRows, number, plurals), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.stt_time_and_speed, latency(median(modelRows.mapNotNull { it.sttMs }), locale), realtime(modelRows, locale)), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text(stringResource(R.string.stt_stats_explanation), style = MaterialTheme.typography.bodySmall)
            }
            Section(stringResource(R.string.processing_time)) {
                StatLine(stringResource(R.string.stt_median), latency(median(data.successful.mapNotNull { it.sttMs }), locale))
                StatLine(stringResource(R.string.total_median), latency(median(data.successful.mapNotNull { it.totalMs }), locale))
                StatLine(stringResource(R.string.rewrite_median), latency(median(data.successful.mapNotNull { it.rewriteMs }), locale))
                Text(stringResource(R.string.total_latency_explanation), style = MaterialTheme.typography.bodySmall)
            }
            Section(stringResource(R.string.apps)) {
                val apps = data.successful.groupBy { it.targetPackage }.entries.sortedByDescending { (_, rows) -> rows.sumOf { (it.rawWords ?: 0).toLong() } }.take(5)
                if (apps.isEmpty()) Text(stringResource(R.string.no_target_apps))
                apps.forEach { (pkg, rows) -> StatLine(pkg?.let { appNames[it] ?: it } ?: stringResource(R.string.unknown_app), plurals.wordCount(rows.sumOf { (it.rawWords ?: 0).toLong() }, number)) }
            }
            Section(stringResource(R.string.modes)) {
                val total = data.successful.size
                Mode.entries.forEach { mode ->
                    val rows = data.successful.filter { it.mode == mode.name }
                    val percent = if (total > 0) (rows.size * 100.0 / total).roundToInt() else 0
                    val label = stringResource(mode.labelRes)
                    val share = percentFormat.format(percent / 100.0)
                    StatLine(label, stringResource(R.string.count_and_share, number.format(rows.size), share))
                    StatLine(stringResource(R.string.mode_raw_to_final, label), stringResource(R.string.counts_raw_to_final,
                        number.format(rows.sumOf { (it.rawWords ?: 0).toLong() }),
                        number.format(rows.sumOf { (it.finalWords ?: 0).toLong() })))
                }
                StatLine(stringResource(R.string.raw_to_final), stringResource(R.string.counts_raw_to_final, number.format(data.words), number.format(data.finalWords)))
                Text(stringResource(R.string.final_words_explanation), style = MaterialTheme.typography.bodySmall)
            }
            Text(stringResource(R.string.dashboard_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun ActivityCalendar(
    data: UsageSummary, period: StatsPeriod, today: LocalDate, locale: Locale,
    number: NumberFormat, plurals: Resources
) {
    val colors = listOf(MaterialTheme.colorScheme.surfaceVariant, Color(0xFFF1C4A7), Color(0xFFD68F65), Color(0xFF9B4E32))
    fun level(words: Long) = when { words == 0L -> 0; words < 100 -> 1; words < 500 -> 2; else -> 3 }
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale) }
    @Composable fun cell(day: LocalDate?, modifier: Modifier, showDay: Boolean) {
        val words = day?.let { data.days[it] } ?: 0L
        val description = day?.let { stringResource(R.string.calendar_day_description, it.format(dateFormat), plurals.wordCount(words, number)) }
        Box(modifier.clip(RoundedCornerShape(4.dp)).background(if (day == null || day > today) Color.Transparent else colors[level(words)])
            .semantics { if (description != null) contentDescription = description }, contentAlignment = Alignment.Center) {
            if (showDay && day != null) Text(number.format(day.dayOfMonth), style = MaterialTheme.typography.labelSmall,
                color = if (words >= 500 && day <= today) Color.White else MaterialTheme.colorScheme.onSurface)
        }
    }
    if (period == StatsPeriod.TODAY) {
        Text(today.format(dateFormat), style = MaterialTheme.typography.bodySmall)
        cell(today, Modifier.size(36.dp), true)
    } else if (period != StatsPeriod.ALL) {
        val first = if (period == StatsPeriod.MONTH) today.withDayOfMonth(1) else StatsPeriod.WEEK.start(today)!!
        val days = if (period == StatsPeriod.MONTH) first.lengthOfMonth() else 7
        val offset = first.dayOfWeek.value - 1
        Text(if (period == StatsPeriod.MONTH) today.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMM"), locale)) else stringResource(R.string.this_week), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DayOfWeek.entries.forEach { Text(it.getDisplayName(TextStyle.SHORT, locale), Modifier.weight(1f), style = MaterialTheme.typography.labelSmall) }
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
        Text(stringResource(R.string.calendar_all_time), style = MaterialTheme.typography.bodySmall)
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
        Text(stringResource(R.string.calendar_legend), style = MaterialTheme.typography.labelSmall)
    }
}
