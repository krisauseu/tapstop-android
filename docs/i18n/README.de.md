# TapStop für Android

Sprache: [English](../../README.md) | Deutsch

## 1. Projektüberblick

TapStop ist eine systemweite Spracheingabe für Android. **Tippen → sprechen → tippen → Text.** Eine schwebende Blase startet und stoppt die Aufnahme. TapStop fügt das Ergebnis am Cursor in das fokussierte Eingabefeld einer anderen App ein. Schlägt die direkte Einfügung fehl, kopiert TapStop das Ergebnis in die Zwischenablage.

## 2. Funktionen

- Verschiebbares Accessibility-Overlay mit Auswahlfächer für vier Modi.
- Getrennte Wahl der Anbieter für Sprache zu Text (STT) und Textüberarbeitung.
- Persönliches Wörterbuch, lokale Nutzungsstatistik sowie getrennt zuschaltbare Audio- und Textdiagnose.

## 3. Screenshots

Geplant sind Screenshots des Dashboards, der Provider-Einstellungen und der Blase mit den vier Modi. Öffentliche Screenshots stehen noch aus.

## 4. Anbieter für Sprache zu Text

| Anbieter | Sprache zu Text | Überarbeitung in Plus, Chat und Formal |
| --- | --- | --- |
| OpenAI | `whisper-1` | `gpt-4o-mini`, Temperatur 0,3 |
| Groq | `whisper-large-v3` oder `whisper-large-v3-turbo` | `openai/gpt-oss-120b`, Temperatur 0,2 für Plus und Formal, 0,4 für Chat |
| Google Gemini, manuell wählbar | `gemini-3.5-transcribe`, wortgetreu mit dem Sprachhinweis `de-DE` | Separat ausgewähltes OpenAI oder Groq |

STT und Überarbeitung lassen sich unabhängig kombinieren, etwa Gemini für die Transkription und Groq für die Überarbeitung. Die automatische Überarbeitung folgt bei OpenAI und Groq dem gewählten STT-Anbieter. Bei Gemini verwendet sie die letzte STT-Auswahl von OpenAI oder Groq. Neue Profile beginnen mit OpenAI. TapStop legt den STT-Anbieter beim Aufnahmestart fest und liest Modus und Überarbeitungseinstellungen beim Stopp.

Gemini sendet die originale M4A über die Google Interactions API mit `store=false`. Überschreitet der Base64-kodierte Request einschließlich JSON 20 MB, lädt TapStop das Audio über die Files API hoch und versucht, es nach Erfolg, Fehler oder Abbruch zu löschen. Ein Verbindungsverlust kann die Löschung verhindern. Google löscht hochgeladene Dateien nach 48 Stunden automatisch. Gemini akzeptiert Aufnahmen bis zu einer Stunde. Bei Gemini-Diktaten wird das persönliche Wörterbuch bei der Überarbeitung mit OpenAI oder Groq verwendet. [Transkription](https://aistudio.google.com/docs/transcribe) · [Files API](https://aistudio.google.com/docs/files)

## 5. Cloud-Releases

Ab v0.12 nutzen die GitHub-APKs OpenAI, Groq und Gemini für die Spracherkennung. Falls in deiner Installation aus einem internen Build noch Local gewählt ist, wähle in den Einstellungen ausdrücklich einen Cloud-Anbieter und hinterlege den passenden API-Key. TapStop behält die vorhandenen Einstellungen und Daten und wartet vor der Aufnahme auf deine Auswahl.

Das experimentelle Qualcomm-Backend und der Modellimport bleiben für die interne Entwicklung verfügbar. [Interner Entwicklungsbuild](../../RELEASING.md#internal-development-build)

## 6. Textverarbeitungsmodi

- **Roh** fügt das STT-Transkript ohne Überarbeitungsaufruf ein.
- **Plus** korrigiert Rechtschreibung, Zeichensetzung und eindeutige Grammatikfehler und entfernt reine Zögerlaute. Wortwahl, Satzreihenfolge, persönlicher Ton und Bedeutung sollen erhalten bleiben.
- **Chat** macht aus dem Diktat eine natürliche, lockere Nachricht mit sparsamen passenden Emojis.
- **Formal** verwendet einen sachlichen Stil mit Absätzen und ohne Emojis.

Halte die Blase im Ruhezustand **480 ms** gedrückt, um den Fächer zu öffnen. Tippe auf einen Modus, um ihn zu speichern, oder ziehe die Blase an eine andere Position. Modus und Position bleiben über Neustarts der App erhalten.

## 7. Installation

TapStop benötigt Android 10 oder neuer. Lade APK und SHA-256-Prüfsumme von [GitHub Releases](https://github.com/krisauseu/tapstop-android/releases) herunter und prüfe die Prüfsumme vor der Installation. Android kann dich bitten, deinem Browser oder Dateimanager die Installation aus dieser Quelle zu erlauben.

Version 0.12 verwendet die Application-ID und den Kotlin-Namespace `de.kf.tapstop`, Versionscode `12` und ein eigenes Release-Zertifikat. Android installiert sie parallel zu `de.kf.blitztext` aus v0.11. Behalte die alte Installation während der Prüfung der neuen App. Spätere Updates für `de.kf.tapstop` benötigen eine kompatible Signatur und verwenden denselben Datenbereich. Siehe [Migrationsanleitung](../../RELEASING.md#package-migration-from-v011).

Version 0.11 stellte Blase und Fächer auf `TYPE_ACCESSIBILITY_OVERLAY` um. Version 0.10 ergänzte Gemini 3.5 Transcribe und die getrennte Wahl von STT und Überarbeitung.

## 8. Konfiguration

1. Wähle in **Einstellungen** einen STT-Anbieter. Speichere für OpenAI, Groq oder Gemini den passenden API-Key.
2. Wähle gegebenenfalls ein Groq-STT-Modell. Konfiguriere für Plus, Chat oder Formal OpenAI oder Groq für die Überarbeitung.
3. Erlaube Mikrofonzugriff und aktiviere **TapStop Texteingabe** in den Android-Bedienungshilfen.
4. Starte die Blase aus der sichtbaren TapStop-App. Fokussiere ein Eingabefeld einer anderen App, tippe auf die Blase, sprich und tippe erneut. Während der Aufnahme ist die Blase rot, während der Verarbeitung blau.

Das persönliche Wörterbuch nimmt pro Zeile einen Begriff oder Ausdruck auf, bis zu 200 Einträge oder 8 KiB. OpenAI und Groq STT verwenden Einträge vom Listenanfang bis zum Prompt-Limit. Bei Gemini wird das Wörterbuch bei der Cloud-Überarbeitung genutzt. [Persönliches Wörterbuch](../PERSONAL_DICTIONARY.md)

Wähle für v0.12 Anbieter und Modi erneut, gib Keys wieder ein, übertrage Wörterbucheinträge manuell und positioniere die neue Blase. Alte Statistiken und private Diagnosedateien bleiben im Speicher der alten App.

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

Der Provider-/Modellvergleich zeigt **× Echtzeit** als Summe der Aufnahmedauern geteilt durch die Summe der STT-Zeiten erfolgreicher Diktate mit positiven Werten in beiden Feldern. 30 Sekunden Audio bei einer Sekunde STT ergeben beispielsweise 30×. Ein Strich kennzeichnet fehlende Messwerte. Die absolute STT-Zeit bleibt als Median sichtbar. Statistiken aus internen Builds können weiterhin Local-Diktate enthalten.

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

Der Standardbuild unterstützt die drei Cloud-Anbieter und erzeugt `app/build/outputs/apk/debug/app-debug.apk`. `packageTapStop` baut die signierte Cloud-APK mit externer Signierkonfiguration. [Release- und Signaturablauf](../../RELEASING.md)

JVM und Robolectric prüfen Gesten, Modusfächer, Persistenz, Roh ohne Überarbeitung, Request-Modelle, Temperaturen, exakte Prompts, Modellimport, Targetzuordnung, Runtime-Lebenszyklus, Providertrennung und Local-Statistik. Die separate Geräte-Instrumentierung nutzt eine temporäre Datenbank und testet Wortzählung, Mediane, gewichtete WPM, Fehlermetriken, Zeitraumgrenzen, Schema, Persistenz, Reset, native InputConnection und Clipboard-Fallback. Der Runner verbindet die aktivierte TapStop-Bedienungshilfe erneut und erhält andere aktivierte Dienste. [Gerätetests](../../DEVICE_TESTS.md) · [Einfügetests](../../INSERT_DEVICE_TESTS.md) · [Instrumentierung ausführen](../../RELEASING.md#device-tests)

## 13. Gerätekompatibilität

TapStop nutzt die Mikrofon- und Bedienungshilfe-Schnittstellen von Android 10 und neuer. Die bisherigen Gerätetests umfassen ein Samsung Galaxy S24 Ultra und ein HONOR MagicPad 4. [0.12-Prüfprotokoll](../RELEASE_0.12_VERIFICATION.md)

## 14. Bekannte Einschränkungen

- Release 0.12 startet in einem separaten Android-Datenbereich. Cloud-Keys, Statistik und Accessibility-Freigabe müssen getrennt behandelt werden.
- Auf dem Galaxy S24 Ultra ließ sich die Blase laut Geräteprüfung bei aktivem TalkBack nicht antippen. Die gesprochenen Beschriftungen wurden nicht verifiziert.
- Die Spracherkennung benötigt eine Internetverbindung und einen eigenen API-Key für den ausgewählten Anbieter.

## 15. Mitarbeit

Gib bei Berichten Build-Variante, Android-Version und Schritte zur Reproduktion an. Halte Keys, Aufnahmen, Transkripte, private Diagnosen, SDK-Binaries und Modelldateien aus Commits und Issues heraus. Füge für neue Qualcomm-Targets Gerätebelege für die Ausführung von Encoder und Decoder bei. Pflege die maßgebliche englische README und diese deutsche Übersetzung gemeinsam.

## 16. Lizenz und Drittanbieterhinweise

Der Projektlizenztext für TapStops eigenen Quellcode ist die Apache License 2.0. Die Release-Checkliste beschreibt die Bestätigung der Rechte vor öffentlicher Distribution. TapStop ist eine eigenständige Android-Implementierung, inspiriert von Christoph Magnussens quelloffener macOS-App Blitztext. Deren MIT-Attribution bleibt erhalten. AndroidX und weitere Abhängigkeiten behalten ihre jeweiligen Lizenzen. [Apache License 2.0](../../LICENSE) · [Blitztext-MIT-Hinweis](../../THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt)
