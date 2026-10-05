package de.kf.blitztext

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
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
        if (!hasMicrophone()) { microphone.launch(Manifest.permission.RECORD_AUDIO); return }
        if (!AndroidSettings.canDrawOverlays(this)) {
            startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
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
        var keyInput by remember { mutableStateOf("") }
        val mode = settings.mode
        var provider by remember { mutableStateOf(settings.provider) }
        var groqModel by remember { mutableStateOf(settings.groqModel) }
        var keySaved by remember { mutableStateOf(settings.apiKey().isNotBlank()) }
        val micOk = hasMicrophone()
        val overlayOk = AndroidSettings.canDrawOverlays(this)
        val accessibilityOk = TextInsertService.instance != null
        Scaffold { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Einstellungen", style = MaterialTheme.typography.headlineMedium)
                Text("Einmal tippen: aufnehmen. Noch einmal tippen: transkribieren und einfügen.")
                Spacer(Modifier.height(8.dp))
                Text("Spracherkennung", style = MaterialTheme.typography.titleMedium)
                ModeRow("OpenAI", provider == Provider.OPENAI) {
                    provider = Provider.OPENAI; settings.provider = provider
                    keyInput = ""; keySaved = settings.apiKey(provider).isNotBlank()
                }
                ModeRow("Groq", provider == Provider.GROQ) {
                    provider = Provider.GROQ; settings.provider = provider
                    keyInput = ""; keySaved = settings.apiKey(provider).isNotBlank()
                }
                QualcommSettings(provider == Provider.QUALCOMM_LOCAL, observed) {
                    provider = Provider.QUALCOMM_LOCAL; settings.provider = provider
                    keyInput = ""; keySaved = false
                }
                if (provider.isCloud) {
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
                            keySaved = keyInput.isNotBlank()
                            keyInput = ""
                            Toast.makeText(this@MainActivity, "API-Key gespeichert", Toast.LENGTH_SHORT).show()
                        } catch (e: Exception) {
                            Toast.makeText(this@MainActivity, "Speichern fehlgeschlagen: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                        }
                    }, enabled = keyInput.isNotBlank()) { Text("Key speichern") }
                    OutlinedButton(onClick = {
                        settings.setApiKey("", provider)
                        keySaved = false
                    }, enabled = keySaved) { Text("Key löschen") }
                }
                }
                Text("Transkription", style = MaterialTheme.typography.titleMedium)
                if (provider == Provider.GROQ) {
                    ModeRow("Whisper Large V3", groqModel == GroqModel.LARGE_V3) {
                        groqModel = GroqModel.LARGE_V3; settings.groqModel = groqModel
                    }
                    ModeRow("Whisper Large V3 Turbo", groqModel == GroqModel.LARGE_V3_TURBO) {
                        groqModel = GroqModel.LARGE_V3_TURBO; settings.groqModel = groqModel
                    }
                } else Text(if (provider.isCloud) "OpenAI Whisper-1" else "Whisper Large V3 Turbo FP16", style = MaterialTheme.typography.bodyMedium)
                Text("Textüberarbeitung: Plus / Chat / Formal", style = MaterialTheme.typography.titleMedium)
                ModeRow("Automatisch: ${if (provider.isCloud) "wie Spracherkennung" else "zuletzt gewählter Cloudprovider"}", settings.rewriteFollowsStt) {
                    settings.followSttForRewrite(); refresh++
                }
                Provider.cloudProviders.forEach { choice ->
                    ModeRow(choice.label, !settings.rewriteFollowsStt && settings.rewriteProvider == choice) {
                        settings.rewriteProvider = choice; refresh++
                    }
                }
                Text("Überarbeitung mit ${settings.rewriteProvider.label}: ${if (settings.rewriteProvider == Provider.GROQ) "GPT-OSS 120B" else "GPT-4o mini"}.",
                    style = MaterialTheme.typography.bodySmall)
                if (provider == Provider.QUALCOMM_LOCAL) {
                    Text(if (mode.usesRewrite) "Spracherkennung lokal. Das Transkript wird anschließend zur Überarbeitung an ${settings.rewriteProvider.label} gesendet."
                        else "Roh: Keine Cloud-Verarbeitung.", style = MaterialTheme.typography.bodySmall)
                    if (mode.usesRewrite && settings.apiKey(settings.rewriteProvider).isBlank())
                        Text("Für die Überarbeitung bitte den Key unter ${settings.rewriteProvider.label} speichern.", color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(6.dp))
                Text("Modus", style = MaterialTheme.typography.titleMedium)
                Mode.entries.forEach { choice ->
                    ModeRow("${choice.symbol} ${choice.label}", mode == choice) { settings.mode = choice }
                }
                Text("Blase lange drücken: Modus wählen. Auswahl startet keine Aufnahme.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text("Berechtigungen", style = MaterialTheme.typography.titleMedium)
                PermissionRow("Mikrofon", micOk) { microphone.launch(Manifest.permission.RECORD_AUDIO) }
                PermissionRow("Über anderen Apps anzeigen", overlayOk) {
                    startActivity(Intent(AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                }
                PermissionRow("Bedienungshilfe für direktes Einfügen", accessibilityOk) {
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                Text("Ohne Bedienungshilfe wird der Text in die Zwischenablage kopiert.", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { launchBubble() }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (OverlayService.running) "Blase ist aktiv" else "Floating-Blase starten")
                }
                if (OverlayService.running) {
                    OutlinedButton(onClick = {
                        stopService(Intent(this@MainActivity, OverlayService::class.java))
                        refresh++
                    }, modifier = Modifier.fillMaxWidth()) { Text("Blase beenden") }
                }
                StatisticsSettings(UsageStats.get(this@MainActivity))
                @Suppress("UNUSED_VARIABLE") val keepObserved = observed
            }
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
