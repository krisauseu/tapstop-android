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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
            Toast.makeText(this, "Diese Version benötigt einen Cloud-Anbieter für die Spracherkennung.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!hasMicrophone()) { microphone.launch(Manifest.permission.RECORD_AUDIO); return }
        if (TextInsertService.instance == null) {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        if (settings.provider.isCloud && settings.apiKey().isBlank()) {
            Toast.makeText(this, "Bitte zuerst den ${settings.provider.label} API-Key speichern.", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "Blase konnte nicht gestartet werden: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
        }
    }

    @Composable
    private fun AppScreen() {
        var dashboard by remember { mutableStateOf(true) }
        Scaffold(bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavigationBarItem(selected = dashboard, onClick = { dashboard = true }, icon = { Text("▦") }, label = { Text("Dashboard") })
                NavigationBarItem(selected = !dashboard, onClick = { dashboard = false }, icon = { Text("⚙") }, label = { Text("Einstellungen") })
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
        var dictionaryInput by remember { mutableStateOf(settings.personalDictionary) }
        var dictionaryNotice by remember { mutableStateOf<String?>(null) }
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
                Text("Einstellungen", style = MaterialTheme.typography.headlineMedium)
                Text("Einmal tippen: aufnehmen. Noch einmal tippen: transkribieren und einfügen.")
                Spacer(Modifier.height(8.dp))
                Text("Spracherkennung", style = MaterialTheme.typography.titleMedium)
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
                    Text("Bitte einen verfügbaren Anbieter auswählen.", style = MaterialTheme.typography.bodySmall)
                }
                if (provider.isCloud) ProviderKeyEditor(provider)
                Text("Transkription", style = MaterialTheme.typography.titleMedium)
                if (provider == Provider.GROQ) {
                    ModeRow("Whisper Large V3", groqModel == GroqModel.LARGE_V3) {
                        groqModel = GroqModel.LARGE_V3; settings.groqModel = groqModel
                    }
                    ModeRow("Whisper Large V3 Turbo", groqModel == GroqModel.LARGE_V3_TURBO) {
                        groqModel = GroqModel.LARGE_V3_TURBO; settings.groqModel = groqModel
                    }
                } else Text(when (provider) {
                    Provider.OPENAI -> "OpenAI Whisper-1"
                    Provider.GEMINI -> "${provider.sttModel(groqModel)} · Deutsch (de-DE), wortgetreu"
                    else -> if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED) "Whisper Large V3 Turbo FP16" else "–"
                }, style = MaterialTheme.typography.bodyMedium)
                Text("Persönliches Wörterbuch", style = MaterialTheme.typography.titleMedium)
                Text("Ein Begriff oder Ausdruck pro Zeile. Nur lokal gespeichert. Bei Cloud-Verarbeitung werden passende Schreibweisen als Kontext mitgesendet.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = dictionaryInput,
                    onValueChange = { dictionaryInput = it; dictionaryNotice = null },
                    label = { Text("Eigennamen und Fachbegriffe") },
                    minLines = 4, maxLines = 8,
                    modifier = Modifier.fillMaxWidth()
                )
                val rewriteOnly = if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED) "Gemini und Qualcomm verwenden" else "Gemini verwendet"
                Text("Bis zu 200 Einträge / 8 KiB. OpenAI und Groq verwenden den Anfang bis zum STT-Prompt-Limit. $rewriteOnly das Wörterbuch nur bei der Textüberarbeitung.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    val normalized = PersonalDictionary.normalize(dictionaryInput)
                    val allEntries = dictionaryInput.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
                    settings.personalDictionary = normalized
                    dictionaryInput = normalized
                    dictionaryNotice = if (normalized != allEntries) "Größenlimit erreicht. Nur vollständige Einträge vom Anfang gespeichert."
                        else "Wörterbuch gespeichert."
                }) { Text("Wörterbuch speichern") }
                dictionaryNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                Text("Textüberarbeitung: Plus / Chat / Formal", style = MaterialTheme.typography.titleMedium)
                ModeRow("Automatisch: ${if (provider.supportsRewrite) "wie Spracherkennung" else "zuletzt gewählter OpenAI-/Groq-STT-Anbieter"}", settings.rewriteFollowsStt) {
                    settings.followSttForRewrite(); refresh++
                }
                Provider.rewriteProviders.forEach { choice ->
                    ModeRow(choice.label, !settings.rewriteFollowsStt && settings.rewriteProvider == choice) {
                        settings.rewriteProvider = choice; refresh++
                    }
                }
                Text("Überarbeitung mit ${settings.rewriteProvider.label}: ${if (settings.rewriteProvider == Provider.GROQ) "GPT-OSS 120B" else "GPT-4o mini"}.",
                    style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.QUALCOMM_RUNTIME_INCLUDED && provider == Provider.QUALCOMM_LOCAL) {
                    Text(if (mode.usesRewrite) "Spracherkennung lokal. Das Transkript wird anschließend zur Überarbeitung an ${settings.rewriteProvider.label} gesendet."
                        else "Roh: Keine Cloud-Verarbeitung.", style = MaterialTheme.typography.bodySmall)
                }
                if (settings.rewriteProvider != provider) ProviderKeyEditor(settings.rewriteProvider)
                Spacer(Modifier.height(6.dp))
                Text("Modus", style = MaterialTheme.typography.titleMedium)
                Mode.entries.forEach { choice ->
                    ModeRow("${choice.symbol} ${choice.label}", mode == choice) { settings.mode = choice }
                }
                Text("Blase lange drücken: Modus wählen. Auswahl startet keine Aufnahme.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("Berechtigungen", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Mikrofon", micOk) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                PermissionRow("Bedienungshilfe für Blase und direktes Einfügen", accessibilityOk) {
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Text("Aktiviere „TapStop Texteingabe“ für die Blase. Wenn direktes Einfügen fehlschlägt, wird der Text in die Zwischenablage kopiert.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launchBubble() }, modifier = Modifier.fillMaxWidth()) {
                    Text(when {
                        !running -> "Floating-Blase starten"
                        accessibilityOk -> "Blase ist aktiv"
                        else -> "Blase pausiert · Bedienungshilfe aktivieren"
                    })
                }
                if (running) {
                    OutlinedButton(onClick = {
                        stopService(Intent(this@MainActivity, OverlayService::class.java))
                        refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Blase beenden") }
                }
                if (BuildConfig.LOCAL_DIAGNOSTICS) {
                    var diagnosis by remember { mutableStateOf(DelayedInsertDiagnostics.enabled(this@MainActivity)) }
                    var audioDiagnosis by remember { mutableStateOf(AudioSttDiagnostics.enabled(this@MainActivity)) }
                    var markerNotice by remember { mutableStateOf<String?>(null) }
                    Text("Temporäre Insert-Textdiagnose", style = MaterialTheme.typography.titleMedium)
                    Text("Speichert vollständige Diktate und zurückgelesene Editor-Ausschnitte nur lokal, ohne Backup. Ringpuffer bis 32 MiB; Altersbereinigung beim Schreiben; keine Audiodaten. Deaktivieren stoppt neue Aufzeichnungen, vorhandene Daten bleiben erhalten.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = diagnosis, onCheckedChange = {
                            DelayedInsertDiagnostics.setEnabled(this@MainActivity, it); diagnosis = it
                        })
                        Text(if (diagnosis) "Textdiagnose aktiv" else "Textdiagnose deaktiviert")
                    }
                    Text("Originalaudio für Diagnose speichern", style = MaterialTheme.typography.titleMedium)
                    Text("Vollständige STT-Eingabedateien und Transkripte lokal ohne Backup. Bis 500 MiB; unmarkierte Fälle bis 14 Tage, Bereinigung bei Diagnosearbeit. Fehlerfälle bevorzugt erhalten. Deaktivieren bewahrt vorhandene Daten.", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(checked = audioDiagnosis, onCheckedChange = {
                            AudioSttDiagnostics.setEnabled(this@MainActivity, it); audioDiagnosis = it
                        })
                        Text(if (audioDiagnosis) "Audiodiagnose aktiv" else "Audiodiagnose deaktiviert")
                    }
                    OutlinedButton(enabled = diagnosis || audioDiagnosis, onClick = {
                        markerNotice = DelayedInsertDiagnostics.mark(this@MainActivity)
                    }) { Text("Letzten Einfügefehler markieren") }
                    if (audioDiagnosis) OutlinedButton(onClick = {
                        markerNotice = AudioSttDiagnostics.mark(this@MainActivity, null, "NO_INSERT", System.currentTimeMillis())
                    }) { Text("Letzte abgeschlossene Aufnahme markieren") }
                    Text("Bei STT-Fehler ohne Einfügung die letzte abgeschlossene Aufnahme ausdrücklich auswählen. Zuerst selbst einen Screenshot erstellen, danach markieren. Es wird nur die letzte Einfügung als Kandidat zugeordnet.", style = MaterialTheme.typography.bodySmall)
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
            label = { Text("${provider.label} API-Key${if (keySaved) " (gespeichert)" else ""}") },
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
                    Toast.makeText(this@MainActivity, "API-Key gespeichert", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Speichern fehlgeschlagen: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }, enabled = keyInput.isNotBlank()) { Text("Key speichern") }
            OutlinedButton(onClick = {
                settings.setApiKey("", provider)
                refresh++
            }, enabled = keySaved) { Text("Key löschen") }
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
            Text("$title: ${if (granted) "Aktiv" else "Fehlt"}", modifier = Modifier.weight(1f))
            if (!granted) OutlinedButton(onClick = onClick) { Text("Öffnen") }
        }
    }
}

@Composable
private fun StatisticsSettings(stats: UsageStats) {
    var confirm by remember { mutableStateOf(false) }
    val storageError by stats.storageError.collectAsState()
    Spacer(Modifier.height(12.dp))
    Text("Statistiken", style = MaterialTheme.typography.titleMedium)
    Text("Nur lokale Metadaten. Keine gespeicherten Texte oder Aufnahmen.", style = MaterialTheme.typography.bodySmall)
    if (storageError) Text("Statistik-Speicher nicht verfügbar. Bitte erneut versuchen.", color = MaterialTheme.colorScheme.error)
    OutlinedButton(onClick = { confirm = true }) { Text("Statistiken zurücksetzen") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Statistiken zurücksetzen?") },
        text = { Text("Alle lokalen Nutzungsstatistiken werden gelöscht. API-Keys und Einstellungen bleiben erhalten.") },
        confirmButton = { TextButton(onClick = { stats.reset(); confirm = false }) { Text("Zurücksetzen") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } }
    )
}
