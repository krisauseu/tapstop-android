package de.kf.blitztext

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LocalInspection(
    val hardware: QualcommHardware,
    val compatibility: QualcommCompatibility,
    val model: QualcommModelStatus?,
    val runtimeError: String?
)

/** Large hashes/import and native preparation never run in composition or on the main thread. */
@Composable
internal fun QualcommSettings(selected: Boolean, refresh: Int, onSelect: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val runtime = remember { QualcommRuntime.get(context) }
    val runtimeStatus by runtime.status.collectAsState()
    val verifiedModel by runtime.modelStatus.collectAsState()
    var inspection by remember { mutableStateOf<LocalInspection?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<String?>(null) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(refresh, revision) {
        inspection = withContext(Dispatchers.IO) {
            val hardware = AndroidQualcommHardware.read()
            val compatibility = QualcommTargets.detect(hardware)
            val target = compatibility.target
            runCatching {
                LocalInspection(hardware, compatibility,
                    target?.let { QualcommModelStore(context).inspect(it) },
                    target?.let { QualcommRuntime.availability(context, it) })
            }.getOrElse {
                LocalInspection(hardware, compatibility, null,
                    "Lokaler Speicher oder Runtime nicht verfügbar: ${it.localizedMessage}")
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = inspection?.compatibility?.target
        if (uri != null && target != null) scope.launch {
            busy = true; operation = "Modell wird importiert und geprüft …"
            operation = withContext(Dispatchers.IO) {
                runCatching { QualcommModelStore(context).importDirectory(target, uri).message }
                    .getOrElse { "Import fehlgeschlagen: ${it.localizedMessage}" }
            }
            busy = false; revision++
        }
    }
    val model = verifiedModel?.takeIf { it.directory == inspection?.model?.directory } ?: inspection?.model
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onSelect, enabled = inspection?.compatibility?.selectable == true)
        Column {
            Text(Provider.QUALCOMM_LOCAL.label)
            Text("Whisper Large V3 Turbo · vollständig auf dem Gerät", style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(inspection?.compatibility?.message ?: "Gerätekompatibilität wird geprüft …",
        style = MaterialTheme.typography.bodySmall)
    if (selected) {
        Text("Spracherkennung lokal auf dem Gerät. Maximal 30 Sekunden pro Diktat.",
            style = MaterialTheme.typography.bodySmall)
        inspection?.runtimeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        model?.let { Text(it.message, style = MaterialTheme.typography.bodySmall) }
        Text(runtimeStatus.message, style = MaterialTheme.typography.bodySmall)
        operation?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(null) }, enabled = !busy && inspection?.compatibility?.selectable == true) {
                Text("Modellordner importieren")
            }
        }
        OutlinedButton(onClick = {
            scope.launch {
                busy = true; operation = "Lokales Modell wird vorbereitet …"
                operation = withContext(Dispatchers.IO) {
                    runCatching { runtime.prepare(); "Lokales Modell bereit." }
                        .getOrElse { "Vorbereitung fehlgeschlagen: ${it.localizedMessage}" }
                }
                busy = false; revision++
            }
        }, enabled = !busy && inspection?.model?.state != QualcommModelState.MISSING &&
            inspection?.compatibility?.selectable == true && inspection?.runtimeError == null) {
            Text("Modell prüfen und vorbereiten")
        }
    }
    TextButton(onClick = { details = !details }) { Text(if (details) "Diagnose ausblenden" else "Local-STT-Diagnose") }
    if (details) inspection?.let { info ->
        val target = info.compatibility.target
        val state = when {
            !info.compatibility.selectable -> info.compatibility.state
            model?.state == QualcommModelState.MISSING -> QualcommCompatibilityState.MODEL_MISSING
            model?.state == QualcommModelState.INVALID -> QualcommCompatibilityState.MODEL_INVALID
            info.runtimeError != null || runtimeStatus.phase == QualcommRuntimePhase.FAILED -> QualcommCompatibilityState.RUNTIME_UNAVAILABLE
            else -> info.compatibility.state
        }
        val diagnostic = """
            Gerät: ${info.hardware.manufacturer} ${info.hardware.model}
            SoC: ${info.hardware.socModel} (${info.hardware.socManufacturer})
            ABI: ${info.hardware.abis.joinToString()}
            Qualcomm Target: ${target?.socModel ?: "–"}
            HTP: ${target?.let { "V${it.htpVersion}" } ?: "–"}
            Local STT: $state
            Modell: Whisper Large V3 Turbo FP16
            Modellstatus: ${model?.message ?: "Kein Target"}
            Runtime: ${info.runtimeError ?: runtimeStatus.message}
            Letzter NPU-Status: unbestätigt (Ausführungsnachweis über native Gerätelogs)
        """.trimIndent()
        Text(diagnostic, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("TapStop Local-STT-Diagnose", diagnostic))
        }) { Text("Diagnose kopieren") }
    }
}
