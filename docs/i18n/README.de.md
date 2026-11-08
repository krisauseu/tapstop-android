# TapStop für Android

Sprache: [English](../../README.md) | Deutsch

## 1. Projektüberblick

TapStop ist eine systemweite Spracheingabe für Android. **Tippen → sprechen → tippen → Text.** Eine schwebende Blase startet und stoppt die Aufnahme. TapStop fügt das Ergebnis am Cursor in das fokussierte Eingabefeld einer anderen App ein. Schlägt die direkte Einfügung fehl, kopiert TapStop das Ergebnis in die Zwischenablage.

## 2. Funktionen

- Deutsche und englische Oberfläche mit App-Sprachwahl ab Android 13.
- Verschiebbares Accessibility-Overlay mit Auswahlfächer für vier Modi.
- Getrennte Wahl der Anbieter für Sprache zu Text (STT) und Textüberarbeitung.
- Persönliches Wörterbuch, lokale Nutzungsstatistik sowie getrennt zuschaltbare Audio- und Textdiagnose.
- Experimentelle Spracherkennung auf dem Gerät für zwei validierte Qualcomm-Targets im vollständigen Build, mit separatem Modellimport.

## 3. Screenshots

Geplant sind Screenshots des Dashboards, der Provider-Einstellungen und der Blase mit den vier Modi. Öffentliche Screenshots stehen noch aus.

## 4. Anbieter für Sprache zu Text

| Anbieter | Sprache zu Text | Überarbeitung in Plus, Chat und Formal |
| --- | --- | --- |
| OpenAI | `whisper-1` | `gpt-4o-mini`, Temperatur 0,3 |
| Groq | `whisper-large-v3` oder `whisper-large-v3-turbo` | `openai/gpt-oss-120b`, Temperatur 0,2 für Plus und Formal, 0,4 für Chat |
| Google Gemini, manuell wählbar | `gemini-3.5-transcribe`, wortgetreu mit dem Sprachhinweis `de-DE` | Separat ausgewähltes OpenAI oder Groq |
| Lokal, Qualcomm NPU | Whisper Large V3 Turbo FP16 auf dem Gerät | Separat ausgewähltes OpenAI oder Groq |

STT und Überarbeitung lassen sich unabhängig kombinieren, etwa Gemini für die Transkription und Groq für die Überarbeitung. Die automatische Überarbeitung folgt bei OpenAI und Groq dem gewählten STT-Anbieter. Bei Gemini oder Local verwendet sie die letzte STT-Auswahl von OpenAI oder Groq. Neue Profile beginnen mit OpenAI. TapStop legt den STT-Anbieter beim Aufnahmestart fest und liest Modus und Überarbeitungseinstellungen beim Stopp.

Gemini sendet die originale M4A über die Google Interactions API mit `store=false`. Überschreitet der Base64-kodierte Request einschließlich JSON 20 MB, lädt TapStop das Audio über die Files API hoch und versucht, es nach Erfolg, Fehler oder Abbruch zu löschen. Ein Verbindungsverlust kann die Löschung verhindern. Google löscht hochgeladene Dateien nach 48 Stunden automatisch. Gemini akzeptiert Aufnahmen bis zu einer Stunde. Bei Gemini-Diktaten wird das persönliche Wörterbuch bei der Überarbeitung mit OpenAI oder Groq verwendet. [Transkription](https://aistudio.google.com/docs/transcribe) · [Files API](https://aistudio.google.com/docs/files)

## 5. Lokale Spracherkennung

Der experimentelle Anbieter Local verwendet die Qualcomm NPU. Wähle in den Einstellungen **Modellordner importieren** und das Modellpaket für dein Target. Das etwa 2,2 GB große Paket wird separat von der APK bereitgestellt. Der Importer prüft Target, Runtime-Versionen, Größen und Hashes. Bereite das Modell vor dem Diktat vor und plane für die erste Initialisierung zusätzliche Zeit ein. Local braucht keinen API-Key.

- **Local + Roh** verarbeitet Spracherkennung und Text vollständig auf dem Gerät. TapStop sendet weder Audio noch Transkript an einen Cloud-Anbieter.
- **Local + Plus, Chat oder Formal** transkribiert auf dem Gerät und sendet den resultierenden Text zur Überarbeitung an den konfigurierten Cloud-Anbieter.

Local stoppt automatisch nach 30 Sekunden. Ein weiterer Tap beendet eine kürzere Aufnahme. Die Grenze gilt nur für Local. Der freie Quellcode-Build blendet Local und den Modellimport aus. [Qualcomm-Backend und Modellimport](../QUALCOMM_LOCAL_STT.md) · [Qualcomm-Lizenzinventar](../QUALCOMM_LICENSING.md)

## 6. Textverarbeitungsmodi

- **Roh** fügt das STT-Transkript ohne Überarbeitungsaufruf ein.
- **Plus** korrigiert Rechtschreibung, Zeichensetzung und eindeutige Grammatikfehler und entfernt reine Zögerlaute. Wortwahl, Satzreihenfolge, persönlicher Ton und Bedeutung sollen erhalten bleiben.
- **Chat** macht aus dem Diktat eine natürliche, lockere Nachricht mit sparsamen passenden Emojis.
- **Formal** verwendet einen sachlichen Stil mit Absätzen und ohne Emojis.

Halte die Blase im Ruhezustand **480 ms** gedrückt, um den Fächer zu öffnen. Tippe auf einen Modus, um ihn zu speichern, oder ziehe die Blase an eine andere Position. Modus und Position bleiben über Neustarts der App erhalten.

## 7. Installation

TapStop benötigt Android 10 oder neuer. Die zuletzt veröffentlichte APK und ihre Release Notes stehen unter [GitHub Releases](https://github.com/krisauseu/tapstop-android/releases). Der vollständige Build für v0.13 heißt `TapStop-Android-0.13.apk`.

Version 0.12 führte die Application-ID und den Kotlin-Namespace `de.kf.tapstop` sowie ein eigenes Release-Zertifikat ein. Android installiert sie parallel zu `de.kf.blitztext` aus v0.11. Version 0.13 (Versionscode `13`) aktualisiert 0.12 bei gleicher Signatur direkt und behält dessen App-Daten. Siehe [Migrationsanleitung](../../RELEASING.md#package-migration-from-v011).

Version 0.11 stellte Blase und Fächer auf `TYPE_ACCESSIBILITY_OVERLAY` um. Version 0.10 ergänzte Gemini 3.5 Transcribe und die getrennte Wahl von STT und Überarbeitung.

## 8. Konfiguration

1. Wähle in **Einstellungen** einen STT-Anbieter. Speichere für OpenAI, Groq oder Gemini den passenden API-Key. Importiere und prüfe für Local das passende Modell.
2. Wähle gegebenenfalls ein Groq-STT-Modell. Konfiguriere für Plus, Chat oder Formal OpenAI oder Groq für die Überarbeitung.
3. Erlaube Mikrofonzugriff und aktiviere **TapStop Texteingabe** in den Android-Bedienungshilfen.
4. Starte die Blase aus der sichtbaren TapStop-App. Fokussiere ein Eingabefeld einer anderen App, tippe auf die Blase, sprich und tippe erneut. Während der Aufnahme ist die Blase rot, während der Verarbeitung blau.

Das persönliche Wörterbuch nimmt pro Zeile einen Begriff oder Ausdruck auf, bis zu 200 Einträge oder 8 KiB. OpenAI und Groq STT verwenden Einträge vom Listenanfang bis zum Prompt-Limit. Bei Gemini und Local wird das Wörterbuch bei der Cloud-Überarbeitung genutzt. [Persönliches Wörterbuch](../PERSONAL_DICTIONARY.md)

Wähle für v0.12 Anbieter und Modi erneut, gib Keys wieder ein, übertrage Wörterbucheinträge manuell und positioniere die neue Blase. Importiere Modelle erneut aus dem ursprünglichen Modellordner. Alte Statistiken und private Diagnosedateien bleiben im Speicher der alten App.

### Oberflächensprache

TapStop folgt der bevorzugten Sprachreihenfolge von Android und verwendet Englisch, wenn keine der bevorzugten Sprachen unterstützt wird. Regionale Varianten für Deutsch und Englisch werden unterstützt. Ab Android 13 kannst du unter **Einstellungen → Apps → TapStop → Sprache** zwischen Deutsch, Englisch und der Systemeinstellung wählen. Die genaue Menübezeichnung hängt vom Gerät ab.

Die Oberflächensprache ist unabhängig vom Diktat. Du kannst zum Beispiel bei englischer Oberfläche auf Deutsch oder bei deutscher Oberfläche auf Englisch diktieren, soweit der gewählte Anbieter die jeweilige Diktiersprache unterstützt. Gemini behält seinen bisherigen Sprachhinweis `de-DE`.

## 9. Berechtigungen und Bedienungshilfen

TapStop nutzt Mikrofonzugriff, einen Mikrofon-Vordergrunddienst, Vibration, Netzwerkzugriff und auf unterstützten Android-Versionen Benachrichtigungen. Der Accessibility-Service zeichnet Blase und Modusfächer, erkennt das fokussierte Eingabefeld und fügt Text über InputConnection oder eine Accessibility-Textaktion ein. Die Zwischenablage dient als Fallback für Felder, in denen die direkte Einfügung fehlschlägt. Seit v0.11 läuft die Blase über das Accessibility-Overlay und benötigt keine separate Freigabe **Über anderen Apps anzeigen**.

Wird die Bedienungshilfe getrennt, verschwinden Blase und Fächer. Solange der Mikrofon-Dienst läuft, erscheint die Blase nach erneutem Verbinden wieder. Mit **Blase beenden** lässt sich der Dienst auch bei getrennter Bedienungshilfe stoppen. Starte die Blase nach einem Neustart oder nachdem Android den Mikrofon-Dienst beendet hat erneut aus der sichtbaren App.

## 10. Datenschutz und Datenverarbeitung

- Keys für OpenAI, Groq und Gemini werden getrennt per AES-GCM mit Android Keystore gespeichert und bleiben beim Providerwechsel innerhalb einer Installation erhalten. Android trennt Keystore und private Dateien des alten Pakets von v0.12.
- Cloud-STT sendet die Aufnahme an den gewählten Anbieter. Cloud-Überarbeitung sendet das Transkript und den zutreffenden Wörterbuchkontext. TapStop hat keinen automatischen Cloud-Fallback.
- Temporäres Audio liegt im privaten Cache und wird nach der Verarbeitung gelöscht. TapStop deaktiviert Android-Backups und schließt ab Android 12 App-Daten ausdrücklich von Cloud-Backups und Geräteübertragung aus.
- **Temporäre Insert-Textdiagnose** erfasst Transkripte, Ausgabe und spätere Nachkontrollen des eingefügten Texts. **Originalaudio für Diagnose speichern** sichert Eingabeaudio und zugehörigen Text. Bei einer Neuinstallation sind beide Schalter aus. Sind beide aus, schreibt TapStop keine zusätzlichen Audio-, Text- oder Einfügediagnosen. Ausschalten bewahrt vorhandene Dateien. Die Nutzungsstatistik läuft unabhängig weiter. Diese lokalen Diagnosen sind in Release-Builds verfügbar. ADB-Export über `run-as` braucht einen debuggable Build. [Audio und STT](../AUDIO_STT_DIAGNOSTICS.md) · [Text und Nachkontrollen](../INSERT_DELAYED_DIAGNOSTICS.md) · [Einfügemetadaten](../INSERT_DIAGNOSTICS.md)

## 11. Nutzungsstatistik

TapStop öffnet das Dashboard. **Heute**, **Woche** (Montag–Sonntag), **Monat** und **Gesamt** verwenden die lokale Gerätezeitzone. Breite Displays zeigen die vier Hauptkennzahlen nebeneinander. Die Gesamt-Heatmap deckt die letzten 53 Wochen ab, während Gesamtzahlen die gesamte verfügbare Historie verwenden. Die täglichen Aktivitätsstufen sind 0, 1–99, 100–499 und mindestens 500 Rohwörter.

**Wörter** zählt STT-Rohwörter. **Ø WPM** teilt die Summe der Rohwörter durch alle Aufnahmeminuten einschließlich Stille. Unicode-Buchstaben- und Zifferngruppen zählen als Wörter, einschließlich interner Bindestriche und Apostrophe. Satzzeichen und alleinstehende Emojis zählen nicht. Finale Wortzahlen werden zusätzlich und nach Modus aufgeschlüsselt erfasst.

Eine erfolgreiche Verarbeitung zählt auch, wenn TapStop den Text über den Clipboard-Fallback bereitstellt. Ein späteres manuelles Einfügen kann TapStop nicht messen. Der Erfolgsstatus erfasst die Verarbeitung. Die Übernahme durch die Ziel-App wird soweit möglich separat geprüft. Die monotone Android-Uhr misst STT um den Transkriptionsaufruf, Rewrite um den Überarbeitungsaufruf und die Gesamtzeit vom Aufnahmestopp bis zum fertigen Text. Fehlversuche speichern nur vorhandene Messwerte. Unvollständige Schritte bleiben `NULL`. Fehlversuche sind von Nutzungszahlen und Latenzmedianen ausgeschlossen.

Der Provider-/Modellvergleich zeigt **× Echtzeit** als Summe der Aufnahmedauern geteilt durch die Summe der STT-Zeiten erfolgreicher Diktate mit positiven Werten in beiden Feldern. 30 Sekunden Audio bei einer Sekunde STT ergeben beispielsweise 30×. Ein Strich kennzeichnet fehlende Messwerte. Die absolute STT-Zeit bleibt als Median sichtbar. Local erscheint separat als **Qualcomm NPU / Whisper Large V3 Turbo** (`qualcomm` / `whisper-large-v3-turbo`). Die Modellvorbereitung zählt nicht zur STT-Latenz.

Die private SQLite-Datenbank `usage.db` verwendet Schema-Version 1, Tabelle `dictations` und Index `dictations_timestamp(timestamp)`.

| Spalte | Typ und Bedeutung |
| --- | --- |
| `id` | INTEGER PRIMARY KEY AUTOINCREMENT |
| `timestamp` | INTEGER, Unix-Millisekunden am Aufnahmeende |
| `recording_ms` | INTEGER, tatsächliche Aufnahmedauer |
| `raw_words`, `final_words` | INTEGER, nullable |
| `provider`, `model`, `mode` | TEXT, tatsächliche STT-Konfiguration und Modus |
| `target_package` | TEXT, nullable bei unbekanntem Ziel |
| `stt_ms`, `rewrite_ms`, `total_ms` | INTEGER, nullable |
| `success` | INTEGER, 0 oder 1 |

SQLite-Arbeit läuft auf Hintergrundthreads. Die Statistik verwendet keine zusätzliche Berechtigung, Netzwerkverbindung oder Telemetrie. Das Ziel-Package stammt vom bereits für die Einfügung gewählten Accessibility-Knoten. PackageManager löst sichtbare App-Namen über eine Launcher-Intent-Abfrage auf. Als Fallback erscheint die Paketkennung. TapStop fordert kein `QUERY_ALL_PACKAGES` an und speichert keine App-Liste. Statistik zurücksetzen löscht nach Bestätigung ausschließlich Statistikzeilen.

## 12. Build aus dem Quellcode

Verwende JDK 17 (`JAVA_HOME`), Android SDK Platform 36 und den eingecheckten Gradle-Wrapper. Öffne das Projekt in Android Studio oder führe diesen Befehl aus.

```sh
./gradlew :app:assembleDebug :app:lint :app:testDebugUnitTest :app:assembleDebugAndroidTest
```

Der Standardbuild verwendet freie Quellen und Abhängigkeiten, unterstützt alle drei Cloud-Anbieter und erzeugt `app/build/outputs/apk/debug/app-debug.apk`. Übergib für den vollständigen Build `-PqualcommRuntimeDir=/absolute/path/to/runtime` mit dem festgelegten VoiceAI- und QAIRT-Bundle. `packageTapStop` baut den signierten vollständigen Release mit externen Signierdaten. [Release- und Signaturablauf](../../RELEASING.md) · [Qualcomm-Staging-Werkzeuge](../../tools/qualcomm-whisper/README.md)

JVM und Robolectric prüfen Gesten, Modusfächer, Persistenz, Roh ohne Überarbeitung, Request-Modelle, Temperaturen, exakte Prompts, Modellimport, Targetzuordnung, Runtime-Lebenszyklus, Providertrennung und Local-Statistik. Die separate Geräte-Instrumentierung nutzt eine temporäre Datenbank und testet Wortzählung, Mediane, gewichtete WPM, Fehlermetriken, Zeitraumgrenzen, Schema, Persistenz, Reset, native InputConnection und Clipboard-Fallback. Der Runner verbindet die aktivierte TapStop-Bedienungshilfe erneut und erhält andere aktivierte Dienste. [Gerätetests](../../DEVICE_TESTS.md) · [Einfügetests](../../INSERT_DEVICE_TESTS.md) · [Instrumentierung ausführen](../../RELEASING.md#device-tests)

## 13. Gerätekompatibilität

Die historische Geräteabnahme verwendete echte Mikrofon-Diktate auf diesen Qualcomm-Targets.

| SoC und HTP-Target | Getestetes Gerät |
| --- | --- |
| Snapdragon 8 Gen 3 · SM8650 · HTP V75 | Samsung Galaxy S24 Ultra (SM-S928B) |
| Snapdragon 8 Gen 5 · SM8845 / SM8845P · HTP V81 | HONOR MagicPad 4 (YLE-W09) |

TapStop erkennt den SoC automatisch. Andere Geräte mit passendem Target werden als ungetestet gekennzeichnet. Der vollständige Build deaktiviert Local auf unbekannten Targets. Die Geräteprotokolle decken Roh, Plus mit Groq, Einfügung, Statistik und HTP-Ausführungsnachweise ab. Ergebnisse von Forschungs-Apps und Tests auf aktuellen Geräten lassen die gesonderte Abnahme des integrierten Builds und Tests auf Android 10–12 offen. [Geräteabnahme](../../DEVICE_TESTS.md)

## 14. Bekannte Einschränkungen

- Local ist auf die gelisteten validierten Geräte und Targets begrenzt. Allgemeine Snapdragon-Unterstützung ist nicht nachgewiesen. Diktate über 30 Sekunden mittels Chunking sind für eine spätere Entwicklung vorgesehen.
- Einzelne Aufnahmen nahe 30 Sekunden scheitern mit VoiceAI `SPECTROGRAM FAIL`. TapStop meldet einen Fehler, fügt keinen Teiltext ein und überspringt die Cloud-Überarbeitung. Ein kürzeres Diktat kann das geladene Modell wiederverwenden.
- Release 0.12 startet in einem separaten Android-Datenbereich. Cloud-Keys, Statistik, Modelle und Accessibility-Freigabe müssen getrennt behandelt werden.
- Der freie Build ist für eine spätere F-Droid-Prüfung gedacht und benötigt ein eigenes Konto oder einen eigenen Key für externe STT-Dienste. [Hinweise zu F-Droid](../../RELEASING.md#future-f-droid-integration)

## 15. Mitarbeit

Gib bei Berichten Build-Variante, Android-Version und Schritte zur Reproduktion an. Halte Keys, Aufnahmen, Transkripte, private Diagnosen, SDK-Binaries und Modelldateien aus Commits und Issues heraus. Füge für neue Qualcomm-Targets Gerätebelege für die Ausführung von Encoder und Decoder bei. Pflege die maßgebliche englische README und diese deutsche Übersetzung gemeinsam.

Lege für eine weitere Sprache eine `values-<locale>/strings.xml` neben den englischen Standardressourcen in `app/src/main/res/values/` an. Übersetze alle übersetzbaren Texte und Pluralformen, behalte die Positionsplatzhalter bei und setze `format_language` auf das Sprachkürzel für die Anzeigeformate. Ergänze die Sprache in `res/xml/locales_config.xml` und in `resourceConfigurations` in `app/build.gradle.kts`. Prüfe Fallback, regionale Varianten, Zahlen, Datum und Pluralformen. Gespeicherte Kennungen und Sprachverarbeitungs-Prompts bleiben von den Oberflächenressourcen getrennt. [Android-App-Sprachwahl](https://developer.android.com/guide/topics/resources/app-languages)

## 16. Lizenz und Drittanbieterhinweise

Der Projektlizenztext für TapStops eigenen Quellcode ist die Apache License 2.0. Die Release-Checkliste beschreibt die Bestätigung der Rechte vor öffentlicher Distribution. TapStop ist eine eigenständige Android-Implementierung, inspiriert von Christoph Magnussens quelloffener macOS-App Blitztext. Deren MIT-Attribution bleibt erhalten. AndroidX und weitere Abhängigkeiten behalten ihre jeweiligen Lizenzen. [Apache License 2.0](../../LICENSE) · [Blitztext-MIT-Hinweis](../../THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt)

Die vollständige APK enthält proprietären Qualcomm-VoiceAI- und QAIRT-Objektcode unter den einschlägigen SDK-Bedingungen mit unveränderten Hinweisen. Modellgewichte und für ein Target kompilierte Pakete unterliegen separaten Bedingungen und werden eigenständig importiert. Das Repository enthält den Integrationscode und die Werkzeuge. [Qualcomm-Lizenzinventar](../QUALCOMM_LICENSING.md)
