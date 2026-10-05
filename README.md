# TapStop für Android

TapStop ist eine systemweite Android-Spracheingabe: **Tap → sprechen → Tap → Text.**
Die schwebende Overlay-Blase startet und stoppt die Aufnahme. Das fertige Diktat wird am Cursor in das fokussierte Eingabefeld einer anderen App eingefügt. Falls direkte Einfügung nicht möglich ist, steht der Text über den Clipboard-Fallback bereit.

## Vier Modi

- **Roh:** unverändertes STT-Transkript, ohne Rewrite-Aufruf.
- **Plus:** korrigiert Rechtschreibung, Zeichensetzung und eindeutige Grammatikfehler und entfernt reine Zögerlaute. Wortwahl, Satzreihenfolge, persönlicher Ton und Aussagen sollen erhalten bleiben.
- **Chat:** überarbeitet das Diktat zu einer natürlichen, lockeren Nachricht mit sparsamen passenden Emojis.
- **Formal:** sachlicher Stil mit Absätzen, ohne Emojis.

Ein **480-ms-Long-Press** auf die Blase öffnet im Ruhezustand den Fächer mit den vier Modi. Antippen eines Modus speichert die Auswahl; Ziehen verschiebt die Blase. Die Auswahl bleibt nach Verarbeitung und Neustart bestehen.

## Provider und Modelle

| Provider | Sprache zu Text | Überarbeitung: Plus / Chat / Formal |
| --- | --- | --- |
| OpenAI | `whisper-1` | `gpt-4o-mini`, Temperatur 0,3 |
| Groq | `whisper-large-v3` oder `whisper-large-v3-turbo` | `openai/gpt-oss-120b`, Temperatur 0,2 für Plus/Formal und 0,4 für Chat |
| Lokal – Qualcomm NPU | Whisper Large V3 Turbo FP16, auf dem Gerät | Separat auswählbarer OpenAI- oder Groq-Rewrite |

STT und Textüberarbeitung lassen sich getrennt auswählen, zum Beispiel **Local + Groq-Rewrite**. Ohne eigene Rewrite-Auswahl bleibt bei bestehenden Cloudinstallationen die bisherige Providerpaarung erhalten; Local übernimmt den zuletzt gewählten Cloudprovider. Die vorhandenen Modelle, Temperaturen und Prompts bleiben unverändert.

Die API-Keys werden für OpenAI und Groq getrennt per Android Keystore AES-GCM geschützt und beim Providerwechsel erhalten. **Local benötigt keinen API-Key.** Audio liegt während der Verarbeitung im privaten Cache und wird danach gelöscht. Der STT-Provider wird beim Aufnahmestart festgelegt; Modus und Rewrite-Einstellungen werden am Aufnahmeende übernommen.

**Local + Roh:** Spracherkennung und Textverarbeitung erfolgen vollständig auf dem Gerät; TapStop sendet weder Audio noch Transkript an einen Cloudprovider. **Local + Plus/Chat/Formal:** Die Spracherkennung bleibt lokal; das Transkript wird anschließend an den konfigurierten Cloudprovider zur Überarbeitung gesendet. Bei OpenAI-/Groq-STT wird die Aufnahme zum gewählten STT-Provider übertragen. Es gibt keinen automatischen Cloud-Fallback.

### Lokale Spracherkennung

Local unterstützt **maximal 30 Sekunden pro Diktat** und stoppt dann automatisch. Ein weiterer Tap beendet eine kürzere Aufnahme. Diese Grenze gilt ausschließlich für Local. Das etwa 2,2 GB große Modell wird separat importiert und ist nicht in der APK enthalten. Die Qualcomm-Runtime muss im verwendeten APK-Build enthalten sein; der normale Quellcode-Build benötigt keine proprietären SDK-Dateien.

**Bekannte SDK-Grenze:** Bei bestimmten Aufnahmen nahe 30 Sekunden kann VoiceAI mit `SPECTROGRAM FAIL` scheitern. TapStop zeigt dann einen Fehler, fügt keinen Teiltext ein und startet keinen Cloud-Rewrite. Ein neues, kürzeres Diktat kann ohne erneutes Laden des Modells gestartet werden. Der automatische Stopp ist keine Garantie für eine erfolgreiche Erkennung jeder Aufnahme.

Aktuell sind zwei lokale Modelltargets in TapStop mit echten Mikrofon-Diktaten validiert:

| SoC/Target | Konkret getestetes Gerät |
| --- | --- |
| Snapdragon 8 Gen 3 · SM8650 · HTP V75 | Samsung Galaxy S24 Ultra |
| Snapdragon 8 Gen 5 · SM8845 / SM8845P · HTP V81 | HONOR MagicPad 4 |

TapStop erkennt den SoC automatisch. Andere Gerätemodelle mit einem bekannten passenden Target werden als **noch nicht am Gerät getestet** gekennzeichnet. Geräte ohne bekanntes Modelltarget sehen die deaktivierte Local-Option. Daraus folgt keine pauschale Unterstützung aller Snapdragon-Geräte. Die [Geräteabnahme](DEVICE_TESTS.md) dokumentiert Roh, Plus mit Groq, Einfügung, Statistik und HTP-Nachweise sowie die bekannte SDK-Grenze.

Modellimport, lokale SDK-Builds, Grenzen und Erweiterung auf weitere SoCs: [Qualcomm-Backend](docs/QUALCOMM_LOCAL_STT.md). Herkunft und Weitergaberechte: [Lizenzinventar](docs/QUALCOMM_LICENSING.md).

## Nutzung

1. APK als Update installieren und einen STT-Provider auswählen. Für OpenAI/Groq den passenden API-Key speichern; für Local das passende Modellpaket über **Modellordner importieren** auswählen.
2. Bei Groq zwischen Whisper Large V3 und Large V3 Turbo wählen. Bei Local das Modell prüfen/vorbereiten; die erste Initialisierung benötigt zusätzliche Zeit. Für Plus/Chat/Formal einen Cloudprovider für die Textüberarbeitung konfigurieren.
3. Mikrofon und „Über anderen Apps anzeigen“ erlauben. Für direktes Einfügen **TapStop Texteingabe** unter Bedienungshilfen aktivieren.
4. Die Overlay-Blase aus der sichtbaren App starten und in einer anderen App ein Textfeld fokussieren.
5. Blase tippen, sprechen, erneut tippen. Während der Aufnahme wird die Blase rot, während der Verarbeitung blau.

Die Android-Plattform beschränkt Mikrofon-Foreground-Services. Nach einem Neustart oder wenn Android den Service beendet, die Blase in TapStop erneut starten.

## Version und Build

Aktuelle Version: **0.9**, `versionCode=9`: Local Qualcomm NPU als dritter STT-Provider, Modellimport und separate Rewrite-Auswahl.

SDK 36 und JDK 17 sind erforderlich. Für den Build muss `JAVA_HOME` auf JDK 17 zeigen. Mit Android Studio öffnen oder ausführen:

```sh
./gradlew assembleDebug lint testDebugUnitTest packageTapStop
```

Die Standardausgabe ist `app/build/outputs/apk/debug/app-debug.apk`; die benannte APK liegt unter **`app/build/outputs/apk/tapstop/TapStop-Android-0.9.apk`**. Sie wird weiterhin mit dem vorhandenen lokalen Debug-Schlüssel signiert. Für Updates auf eine bisherige Installation muss dieselbe Signatur verwendet werden; ein anderer Rechner hat üblicherweise einen anderen Debug-Schlüssel. Signaturschlüssel gehören nicht ins Repository.

Ein Build mit lokal bereitgestellter Qualcomm-Runtime verwendet zusätzlich `-PqualcommRuntimeDir=/path/to/local-runtime`. Struktur und Werkzeuge stehen in [tools/qualcomm-whisper](tools/qualcomm-whisper/README.md). Qualcomm-Dateien und Modellgewichte gehören nicht ins öffentliche Repository.

Die JVM-/Robolectric-Tests prüfen weiterhin Gesten, Modus-Fächer, Persistenz, Roh-Verarbeitung ohne Rewrite-Netzwerk sowie Request-Modelle, Temperaturen und exakte Prompttexte beider Cloudprovider. Hinzu kommen Targetzuordnung, Modellprüfung/Import, der lokale Runtime-Lifecycle, Providertrennung und Local-Statistik. Aktuelle und historische Geräteprüfungen stehen in [DEVICE_TESTS.md](DEVICE_TESTS.md) und [INSERT_DEVICE_TESTS.md](INSERT_DEVICE_TESTS.md). Die PoC-Abnahmen ersetzen keine Geräteabnahme der integrierten App.

## Update- und Datenkompatibilität

Die sichtbare App heißt TapStop. Technische Identitäten bleiben bewusst erhalten:

- `applicationId` und Kotlin-Namespace: `de.kf.blitztext`.
- Accessibility-Komponente: `de.kf.blitztext/de.kf.blitztext.TextInsertService`.
- SharedPreferences: `settings`, einschließlich der bisherigen Schlüssel für Provider, Modi, Blasenposition und API-Keys.
- Android-Keystore-Alias: `blitztext_api_key`.
- SQLite: `usage.db`, Schema Version 1; der gespeicherte Moduswert `BLITZTEXT` bezeichnet weiterhin den sichtbaren Modus **Roh**.
- Benachrichtigungskanal-ID: `blitztext`; dessen sichtbarer Name lautet **TapStop-Blase**.

Es gibt keine Package- oder Datenmigration und keine absichtliche Löschung von Einstellungen, API-Keys oder Statistiken. Ein Update mit gleicher Application-ID und Signatur erhält den privaten App-Datenbereich. Die App nicht deinstallieren und ihre Daten nicht löschen.

## Inspiration und Lizenzhinweis

TapStop wurde ursprünglich von Christoph Magnussens quelloffener macOS-App Blitztext inspiriert und ist eine eigenständige Android-Implementierung. Der vorhandene MIT-Lizenztext zur ursprünglichen macOS-Vorlage bleibt in [THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt](THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt) erhalten. Die Umbenennung verändert die bestehenden Lizenzhinweise nicht.

## Lokale Statistiken (ab 0.3)

Die App startet im Dashboard. Die bisherigen Funktionen sind unter **Einstellungen** unverändert erreichbar. Heute, Woche (Montag–Sonntag), aktueller Kalendermonat und Gesamt werden anhand der lokalen Gerätezeitzone gefiltert. Auf breiten Displays stehen alle vier Hauptkennzahlen nebeneinander. Die Gesamt-Heatmap zeigt die letzten 53 Wochen; Gesamtzahlen berücksichtigen die gesamte gespeicherte Historie. Die Aktivitätsstufen sind 0, 1–99, 100–499 und mindestens 500 Rohwörter pro Tag.

**Wörter** meint STT-Rohwörter; **Ø WPM** ist die Summe der Rohwörter geteilt durch die gesamte Aufnahmedauer in Minuten (gewichtet, keine Stillebereinigung). Die lokale Unicode-Wortzählung zählt Buchstaben-/Zifferngruppen; interne Bindestriche und Apostrophe bleiben Teil eines Wortes. Satzzeichen und alleinstehende Emojis zählen nicht als Wörter. Finale Wortzahlen werden zusätzlich und ab 0.6 für jeden Modus separat gezeigt.

Eine erfolgreiche Verarbeitung zählt auch dann, wenn das Ergebnis über den bestehenden Clipboard-Fallback bereitgestellt wird. Das spätere manuelle Einfügen ist nicht messbar. Erfolgsstatus bezeichnet die Verarbeitung, nicht eine bestätigte Übernahme durch die Ziel-App. Latenzen werden mit Androids monotoner Uhr gemessen: STT um den Transkriptionsaufruf, Rewrite um die Überarbeitung und Gesamt vom Stoppen der Aufnahme bis zum fertigen Text. Fehlgeschlagene Verarbeitungen speichern nur bereits tatsächlich ermittelte Werte; unvollständige Schritte bleiben `NULL`. Fehlversuche sind von Nutzungszahlen und Latenzmedianen ausgeschlossen.

Ab 0.4 zeigt der Provider-/Modellvergleich zusätzlich **× Echtzeit**: Summe `recording_ms` / Summe `stt_ms` im gewählten Zeitraum, kein Mittelwert einzelner Faktoren. Nur erfolgreiche Diktate mit positiven Aufnahme- und STT-Zeiten gehen in beide Summen ein; ohne messbare Zeiten erscheint ein Strich. 30 Sekunden Audio bei 1 Sekunde STT ergeben 30× Echtzeit. Die absolute **STT-Zeit** bleibt als Median sichtbar. Vorhandene Statistiken aus 0.3 werden ohne Migration weiterverwendet.

Local erscheint separat als **Qualcomm NPU / Whisper Large V3 Turbo**. Die bestehenden Felder speichern `provider=qualcomm`, `model=whisper-large-v3-turbo`, Modus sowie Aufnahme-, STT-, Rewrite- und Gesamtzeit. Die vorbereitende Modellinitialisierung gehört nicht zur STT-Latenz. Das Datenbankschema bleibt unverändert.

Speicherung: privates SQLite `usage.db`, Schema Version 1, Tabelle `dictations`:

| Spalte | Typ / Bedeutung |
| --- | --- |
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `timestamp` | INTEGER, Unix-Millisekunden am Aufnahmeende |
| `recording_ms` | INTEGER, tatsächliche Aufnahmedauer |
| `raw_words`, `final_words` | INTEGER, nullable |
| `provider`, `model`, `mode` | TEXT, tatsächlich verwendete STT-Konfiguration und Modus |
| `target_package` | TEXT, nullable = Unbekannt |
| `stt_ms`, `rewrite_ms`, `total_ms` | INTEGER, nullable |
| `success` | INTEGER, 0 oder 1 |

Index: `dictations_timestamp(timestamp)`. SQLite läuft auf Hintergrundthreads. Die Statistik benötigt keine zusätzliche Berechtigung, Cloud-Anbindung oder Telemetrie. Das Ziel-Package wird ausschließlich vom bereits für die Einfügung gewählten Accessibility-Knoten gelesen; die Einfügelogik bleibt unberührt. App-Namen werden soweit sichtbar mit PackageManager aufgelöst, sonst wird das Package angezeigt. Eine Launcher-Intent-Abfrage erlaubt die Namensauflösung gewöhnlicher startbarer Apps, ohne `QUERY_ALL_PACKAGES` oder zusätzliche Berechtigung. Es wird keine Liste installierter Apps gespeichert. `allowBackup=false` gilt weiterhin; Android-12+-Datenextraktionsregeln schließen Cloud-Backups und Geräteübertragungen explizit aus. API-Keys und Einstellungen bleiben in den bestehenden Preferences/Keystore-Einträgen. Statistiken zurücksetzen löscht nach Bestätigung ausschließlich die Statistikzeilen.

## Automatisierter Gerätetest

`assembleDebugAndroidTest` erzeugt eine separate Test-APK ohne zusätzliche Testbibliothek. Nach Installation beider APKs:

```sh
python3 scripts/device-tests.py
```

Die Testdaten liegen in `instrumentation-usage.db`, werden anschließend entfernt und berühren die echten Statistiken nicht. Geprüft werden Wortzählung, Mediane, gewichtete WPM, Fehler mit Teilmetriken, Zeitraumgrenzen, Datenbankschema, Persistenz, Reset sowie native InputConnection-Fälle und Clipboard-Fallback. Das Skript verbindet den bereits aktivierten Dienst nach dem Prozessneustart des Test-Runners erneut und erhält dabei die ursprüngliche Liste aller aktivierten Dienste. Der Test benötigt die aktivierte TapStop-Bedienungshilfe und einen bereits konfigurierten Provider. Für echte Mikrofon-/Provider-Tests weiterhin zwei Diktate in Roh und Plus durchführen. Ein einzelnes modernes Testgerät ersetzt keinen Lauf auf Android 10–12.
