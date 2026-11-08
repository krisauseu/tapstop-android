package de.kf.tapstop

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class LocalInspection(
    val hardware: QualcommHardware,
    val compatibility: QualcommCompatibility,
    val model: QualcommModelStatus?,
    val runtimeError: UiMessage?
)

/** Large hashes/import and native preparation never run in composition or on the main thread. */
@Composable
internal fun QualcommSettings(selected: Boolean, refresh: Int, onSelect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runtime = remember { QualcommRuntime.get(context) }
    val runtimeStatus by runtime.status.collectAsState()
    val verifiedModel by runtime.modelStatus.collectAsState()
    var inspection by remember { mutableStateOf<LocalInspection?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var operation by remember { mutableStateOf<UiMessage?>(null) }
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
                    it.uiMessage(R.string.local_inspection_failed))
            }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val target = inspection?.compatibility?.target
        if (uri != null && target != null) scope.launch {
            busy = true; operation = UiMessage(R.string.model_importing)
            operation = withContext(Dispatchers.IO) {
                runCatching { QualcommModelStore(context).importDirectory(target, uri).message }
                    .getOrElse { it.uiMessage(R.string.import_failed) }
            }
            busy = false; revision++
        }
    }
    val model = verifiedModel?.takeIf { it.directory == inspection?.model?.directory } ?: inspection?.model
    Row(verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = onSelect, enabled = inspection?.compatibility?.selectable == true)
        Column {
            Text(stringResource(R.string.local_experimental))
            Text(stringResource(R.string.local_model_and_backend), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text(stringResource(R.string.local_experimental_description),
        style = MaterialTheme.typography.bodySmall)
    Text(inspection?.compatibility?.message?.resolve(context) ?: stringResource(R.string.checking_compatibility),
        style = MaterialTheme.typography.bodySmall)
    if (selected) {
        Text(stringResource(R.string.local_duration_limit),
            style = MaterialTheme.typography.bodySmall)
        inspection?.runtimeError?.let { Text(it.resolve(context), color = MaterialTheme.colorScheme.error) }
        model?.let { Text(it.message.resolve(context), style = MaterialTheme.typography.bodySmall) }
        Text(runtimeStatus.message.resolve(context), style = MaterialTheme.typography.bodySmall)
        operation?.let { Text(it.resolve(context), style = MaterialTheme.typography.bodySmall) }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { importer.launch(null) }, enabled = !busy && inspection?.compatibility?.selectable == true) {
                Text(stringResource(R.string.import_model_folder))
            }
        }
        OutlinedButton(onClick = {
            scope.launch {
                busy = true; operation = UiMessage(R.string.local_preparing)
                operation = withContext(Dispatchers.IO) {
                    runCatching { runtime.prepare(); UiMessage(R.string.local_ready) }
                        .getOrElse { it.uiMessage(R.string.prepare_failed) }
                }
                busy = false; revision++
            }
        }, enabled = !busy && inspection?.model?.state != QualcommModelState.MISSING &&
            inspection?.compatibility?.selectable == true && inspection?.runtimeError == null) {
            Text(stringResource(R.string.prepare_model))
        }
    }
    TextButton(onClick = { details = !details }) { Text(if (details) stringResource(R.string.hide_diagnostics) else stringResource(R.string.local_diagnostics)) }
    if (details) inspection?.let { info ->
        val target = info.compatibility.target
        val state = when {
            !info.compatibility.selectable -> info.compatibility.state
            model?.state == QualcommModelState.MISSING -> QualcommCompatibilityState.MODEL_MISSING
            model?.state == QualcommModelState.INVALID -> QualcommCompatibilityState.MODEL_INVALID
            info.runtimeError != null || runtimeStatus.phase == QualcommRuntimePhase.FAILED -> QualcommCompatibilityState.RUNTIME_UNAVAILABLE
            else -> info.compatibility.state
        }
        val dash = stringResource(R.string.dash)
        val diagnostic = stringResource(
            R.string.local_diagnostic_details,
            info.hardware.manufacturer, info.hardware.model, info.hardware.socModel,
            info.hardware.socManufacturer, info.hardware.abis.joinToString(), target?.socModel ?: dash,
            target?.let { "V${it.htpVersion}" } ?: dash, state.name,
            model?.message?.resolve(context) ?: stringResource(R.string.no_target),
            (info.runtimeError ?: runtimeStatus.message).resolve(context)
        )
        Text(diagnostic, style = MaterialTheme.typography.bodySmall)
        val clipLabel = stringResource(R.string.local_diagnostics_clip)
        OutlinedButton(onClick = {
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText(clipLabel, diagnostic))
        }) { Text(stringResource(R.string.copy_diagnostics)) }
    }
}
