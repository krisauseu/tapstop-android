package de.kf.tapstop

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var settings: Settings
    private var refresh by mutableIntStateOf(0)
    private val modeObserver = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("mode", "provider", "rewrite_provider")) refresh++
    }
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        settings = Settings(this)
        settings.observeMode(modeObserver)
        setContent { MaterialTheme(colorScheme = WarmColors) { AppScreen() } }
    }

    override fun onDestroy() { settings.removeModeObserver(modeObserver); super.onDestroy() }

    override fun onResume() { super.onResume(); refresh++ }

    private fun hasMicrophone() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun launchBubble() {
        if (!BuildConfig.QUALCOMM_RUNTIME_INCLUDED && settings.provider == Provider.QUALCOMM_LOCAL) {
            Toast.makeText(this, getString(R.string.cloud_provider_required), Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicrophone()) { microphone.launch(Manifest.permission.RECORD_AUDIO); return }
        if (TextInsertService.instance == null) {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (settings.provider.isCloud && settings.apiKey().isBlank()) {
            Toast.makeText(this, getString(R.string.save_provider_key_first, settings.provider.label), Toast.LENGTH_SHORT).show()
            return
        }
        try {
            ContextCompat.startForegroundService(this, Intent(this, OverlayService::class.java))
            refresh++
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.bubble_start_failed), Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun AppScreen() {
        var dashboard by rememberSaveable { mutableStateOf(true) }
        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationBarItem(selected = dashboard, onClick = { dashboard = true }, icon = { Text("▦") }, label = { Text(stringResource(R.string.dashboard)) })
                NavigationBarItem(selected = !dashboard, onClick = { dashboard = false }, icon = { Text("⚙") }, label = { Text(stringResource(R.string.settings)) })
            }
        }) { padding ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                androidx.compose.foundation.layout.Box(Modifier.widthIn(max = 960.dp).fillMaxSize()) {
                    if (dashboard) Dashboard(UsageStats.get(this@MainActivity), refresh) else Screen()
                }
            }
        }
    }

    @Composable
    private fun Screen() {
        val observed = refresh
        var dictionaryInput by rememberSaveable { mutableStateOf(settings.personalDictionary) }
        var dictionaryNotice by remember { mutableStateOf<Int?>(null) }
        val mode = settings.mode
        var provider by remember { mutableStateOf(settings.provider) }
        var groqModel by remember { mutableStateOf(settings.groqModel) }
        val micOk = hasMicrophone()
        val accessibility by TextInsertService.connection.collectAsState()
        val accessibilityOk = accessibility != null
        val recordingService by OverlayService.active.collectAsState()
        val running = recordingService != null
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(stringResource(R.string.settings), style = MaterialTheme.typography.headlineMedium)
                Text(stringResource(R.string.tap_instructions))
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.speech_recognition), style = MaterialTheme.typography.titleMedium)
                for (choice in Provider.entries.filter { it.isCloud }) {
                    ModeRow(choice.label, provider == choice) {
                        provider = choice
                        settings.provider = choice
                    }
                }
                if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED) {
                    QualcommSettings(provider == Provider.QUALCOMM_LOCAL, observed) {
                        provider = Provider.QUALCOMM_LOCAL
                        settings.provider = provider
                    }
                } else if (provider == Provider.QUALCOMM_LOCAL) {
                    Text(stringResource(R.string.select_available_provider), style = MaterialTheme.typography.bodySmall)
                }
                if (provider.isCloud) ProviderKeyEditor(provider)
                Text(stringResource(R.string.transcription), style = MaterialTheme.typography.titleMedium)
                if (provider == Provider.GROQ) {
                    ModeRow(stringResource(R.string.whisper_large_v3), groqModel == GroqModel.LARGE_V3) {
                        groqModel = GroqModel.LARGE_V3; settings.groqModel = groqModel
                    }
                    ModeRow(stringResource(R.string.whisper_large_v3_turbo), groqModel == GroqModel.LARGE_V3_TURBO) {
                        groqModel = GroqModel.LARGE_V3_TURBO; settings.groqModel = groqModel
                    }
                } else Text(when (provider) {
                    Provider.OPENAI -> stringResource(R.string.openai_whisper)
                    Provider.GEMINI -> stringResource(R.string.gemini_model_details, provider.sttModel(groqModel))
                    else -> if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED) stringResource(R.string.whisper_large_v3_turbo_fp16) else stringResource(R.string.dash)
                }, style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.personal_dictionary), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.dictionary_description),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = dictionaryInput,
                    onValueChange = { dictionaryInput = it; dictionaryNotice = null },
                    label = { Text(stringResource(R.string.dictionary_input)) },
                    minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(stringResource(if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED) R.string.dictionary_limit_full else R.string.dictionary_limit_cloud),
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val normalized = PersonalDictionary.normalize(dictionaryInput)
                    val allEntries = dictionaryInput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    settings.personalDictionary = normalized
                    dictionaryInput = normalized
                    dictionaryNotice = if (normalized != allEntries) R.string.dictionary_truncated else R.string.dictionary_saved
                }) { Text(stringResource(R.string.save_dictionary)) }
                dictionaryNotice?.let { Text(stringResource(it), style = MaterialTheme.typography.bodySmall) }
                Text(stringResource(R.string.rewrite_modes), style = MaterialTheme.typography.titleMedium)
                ModeRow(stringResource(if (provider.supportsRewrite) R.string.rewrite_follow_stt else R.string.rewrite_follow_last_cloud), settings.rewriteFollowsStt) {
                    settings.followSttForRewrite(); refresh++
                }
                Provider.rewriteProviders.forEach { choice ->
                    ModeRow(choice.label, !settings.rewriteFollowsStt && settings.rewriteProvider == choice) {
                        settings.rewriteProvider = choice; refresh++
                    }
                }
                val rewriteModel = if (settings.rewriteProvider == Provider.GROQ) "GPT-OSS 120B" else "GPT-4o mini"
                Text(stringResource(R.string.rewrite_configuration, settings.rewriteProvider.label, rewriteModel),
                    style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED && provider == Provider.QUALCOMM_LOCAL) {
                    Text(if (mode.usesRewrite) stringResource(R.string.local_with_rewrite, settings.rewriteProvider.label)
                        else stringResource(R.string.local_raw), style = MaterialTheme.typography.bodySmall)
                }
                if (settings.rewriteProvider != provider) ProviderKeyEditor(settings.rewriteProvider)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.mode), style = MaterialTheme.typography.titleMedium)
                Mode.entries.forEach { choice ->
                    ModeRow(stringResource(R.string.mode_bubble, choice.symbol, stringResource(choice.labelRes)), mode == choice) { settings.mode = choice }
                }
                Text(stringResource(R.string.mode_picker_hint), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.permissions), style = MaterialTheme.typography.titleMedium)
                PermissionRow(stringResource(R.string.microphone), micOk) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                PermissionRow(stringResource(R.string.accessibility_permission), accessibilityOk) {
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Text(stringResource(R.string.accessibility_setup, stringResource(R.string.accessibility_service_label)), style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launchBubble() }, modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        !running -> stringResource(R.string.bubble_start)
                        accessibilityOk -> stringResource(R.string.bubble_active)
                        else -> stringResource(R.string.bubble_paused)
                    })
                }
                if (running) {
                    OutlinedButton(onClick = {
                        stopService(Intent(this@MainActivity, OverlayService::class.java))
                        refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.bubble_stop)) }
                }
                if (BuildConfig.LOCAL_DIAGNOSTICS) {
                    var diagnosis by remember { mutableStateOf(DelayedInsertDiagnostics.enabled(this@MainActivity)) }
                    var audioDiagnosis by remember { mutableStateOf(AudioSttDiagnostics.enabled(this@MainActivity)) }
                    var markerNotice by remember { mutableStateOf<String?>(null) }
                    Text(stringResource(R.string.text_diagnostics), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.text_diagnostics_description), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = diagnosis, onCheckedChange = {
                            DelayedInsertDiagnostics.setEnabled(this@MainActivity, it); diagnosis = it
                        })
                        Text(if (diagnosis) stringResource(R.string.text_diagnostics_on) else stringResource(R.string.text_diagnostics_off))
                    }
                    Text(stringResource(R.string.audio_diagnostics), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.audio_diagnostics_description), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = audioDiagnosis, onCheckedChange = {
                            AudioSttDiagnostics.setEnabled(this@MainActivity, it); audioDiagnosis = it
                        })
                        Text(if (audioDiagnosis) stringResource(R.string.audio_diagnostics_on) else stringResource(R.string.audio_diagnostics_off))
                    }
                    OutlinedButton(enabled = diagnosis || audioDiagnosis, onClick = {
                        markerNotice = DelayedInsertDiagnostics.mark(this@MainActivity)
                    }) { Text(stringResource(R.string.mark_insert_error)) }
                    if (audioDiagnosis) OutlinedButton(onClick = {
                        markerNotice = AudioSttDiagnostics.mark(this@MainActivity, null, "NO_INSERT", System.currentTimeMillis())
                    }) { Text(stringResource(R.string.mark_recording)) }
                    Text(stringResource(R.string.mark_diagnostic_hint), style = MaterialTheme.typography.bodySmall)
                    markerNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                StatisticsSettings(UsageStats.get(this@MainActivity))
                @Suppress("UNUSED_VARIABLE") val keepObserved = observed
            }
        }
    }

    @Composable
    private fun ProviderKeyEditor(provider: Provider) {
        var keyInput by remember(provider) { mutableStateOf("") }
        val keySaved = remember(provider, refresh) { settings.apiKey(provider).isNotBlank() }
        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text(stringResource(if (keySaved) R.string.provider_key_saved else R.string.provider_key, provider.label)) },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                try {
                    settings.setApiKey(keyInput, provider)
                    keyInput = ""
                    refresh++
                    Toast.makeText(this@MainActivity, getString(R.string.api_key_saved), Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, getString(R.string.api_key_save_failed), Toast.LENGTH_LONG).show()
                }
            }, enabled = keyInput.isNotBlank()) { Text(stringResource(R.string.save_key)) }
            OutlinedButton(onClick = {
                settings.setApiKey("", provider)
                refresh++
            }, enabled = keySaved) { Text(stringResource(R.string.delete_key)) }
        }
    }

    @Composable
    private fun ModeRow(title: String, selected: Boolean, onClick: () -> Unit) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            RadioButton(selected = selected, onClick = onClick)
            Text(title)
        }
    }

    @Composable
    private fun PermissionRow(title: String, granted: Boolean, onClick: () -> Unit) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(stringResource(R.string.permission_active, title, stringResource(if (granted) R.string.granted else R.string.missing)), modifier = Modifier.weight(1f))
            if (!granted) OutlinedButton(onClick = onClick) { Text(stringResource(R.string.open_settings)) }
        }
    }
}

@Composable
private fun StatisticsSettings(stats: UsageStats) {
    var confirm by remember { mutableStateOf(false) }
    val storageError by stats.storageError.collectAsState()
    Spacer(Modifier.height(12.dp))
    Text(stringResource(R.string.statistics), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.statistics_privacy), style = MaterialTheme.typography.bodySmall)
    if (storageError) Text(stringResource(R.string.statistics_unavailable), color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = { confirm = true }) { Text(stringResource(R.string.reset_statistics)) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.reset_statistics_title)) },
        text = { Text(stringResource(R.string.reset_statistics_message)) },
        confirmButton = { TextButton(onClick = { stats.reset(); confirm = false }) { Text(stringResource(R.string.reset)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } }
    )
}
