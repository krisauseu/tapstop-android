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

Die API-Keys werden für beide Provider getrennt auf dem Gerät gespeichert und per Android Keystore AES-GCM geschützt. Audio liegt während der Verarbeitung im privaten Cache und wird danach gelöscht. Audio und das für die Überarbeitung benötigte Transkript werden an den ausgewählten Provider gesendet. Eine laufende Verarbeitung verwendet die Einstellungen vom Aufnahmeende.

## Nutzung

1. APK als Update installieren, OpenAI oder Groq auswählen und den passenden API-Key speichern.
2. Bei Groq zwischen Whisper Large V3 und Large V3 Turbo wählen.
3. Mikrofon und „Über anderen Apps anzeigen“ erlauben. Für direktes Einfügen **TapStop Texteingabe** unter Bedienungshilfen aktivieren.
4. Die Overlay-Blase aus der sichtbaren App starten und in einer anderen App ein Textfeld fokussieren.
5. Blase tippen, sprechen, erneut tippen. Während der Aufnahme wird die Blase rot, während der Verarbeitung blau.

Die Android-Plattform beschränkt Mikrofon-Foreground-Services. Nach einem Neustart oder wenn Android den Service beendet, die Blase in TapStop erneut starten.

## Version und Build

Aktuelle Version: **0.8**, `versionCode=8`. Die Umbenennung verändert weder Versionsnummer noch Funktion, Provider, Modelle oder Prompts.

SDK 36 und JDK 17 sind erforderlich. Für den Build muss `JAVA_HOME` auf JDK 17 zeigen. Mit Android Studio öffnen oder ausführen:

```sh
./gradlew assembleDebug lint testDebugUnitTest packageTapStop
```

Die Standardausgabe ist `app/build/outputs/apk/debug/app-debug.apk`; die benannte APK liegt unter **`app/build/outputs/apk/tapstop/TapStop-Android-0.8.apk`**. Sie wird weiterhin mit dem vorhandenen lokalen Debug-Schlüssel signiert. Für Updates auf eine bisherige Installation muss dieselbe Signatur verwendet werden; ein anderer Rechner hat üblicherweise einen anderen Debug-Schlüssel. Signaturschlüssel gehören nicht ins Repository.

Lokale Prüfung des TapStop-Stands: Build erfolgreich, **18 Tests bestanden**, Lint **0 Fehler / 21 Hinweise**. APK-Label, Version und unveränderte Signatur geprüft.

Die vorhandenen JVM-/Robolectric-Tests prüfen Gesten, Modus-Fächer, Moduspersistenz, Roh-Verarbeitung ohne Rewrite-Netzwerk sowie Request-Modelle, Temperaturen und exakte Prompttexte beider Provider. Für die Umbenennung werden keine Geräte- oder Live-Provider-Tests benötigt. Historische Geräteprüfungen stehen in [DEVICE_TESTS.md](DEVICE_TESTS.md) und [INSERT_DEVICE_TESTS.md](INSERT_DEVICE_TESTS.md).

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

Index: `dictations_timestamp(timestamp)`. SQLite läuft auf Hintergrundthreads. Keine neue Runtime-Dependency, Berechtigung, Cloud-Anbindung oder Telemetrie. Das Ziel-Package wird ausschließlich vom bereits für die Einfügung gewählten Accessibility-Knoten gelesen; die Einfügelogik bleibt unberührt. App-Namen werden soweit sichtbar mit PackageManager aufgelöst, sonst wird das Package angezeigt. Eine Launcher-Intent-Abfrage erlaubt die Namensauflösung gewöhnlicher startbarer Apps, ohne `QUERY_ALL_PACKAGES` oder zusätzliche Berechtigung. Es wird keine Liste installierter Apps gespeichert. `allowBackup=false` gilt weiterhin; Android-12+-Datenextraktionsregeln schließen Cloud-Backups und Geräteübertragungen explizit aus. API-Keys und Einstellungen bleiben in den bestehenden Preferences/Keystore-Einträgen. Statistiken zurücksetzen löscht nach Bestätigung ausschließlich die Statistikzeilen.

## Automatisierter Gerätetest

`assembleDebugAndroidTest` erzeugt eine separate Test-APK ohne zusätzliche Testbibliothek. Nach Installation beider APKs:

```sh
python3 scripts/device-tests.py
```

Die Testdaten liegen in `instrumentation-usage.db`, werden anschließend entfernt und berühren die echten Statistiken nicht. Geprüft werden Wortzählung, Mediane, gewichtete WPM, Fehler mit Teilmetriken, Zeitraumgrenzen, Datenbankschema, Persistenz, Reset sowie native InputConnection-Fälle und Clipboard-Fallback. Das Skript verbindet den bereits aktivierten Dienst nach dem Prozessneustart des Test-Runners erneut und erhält dabei die ursprüngliche Liste aller aktivierten Dienste. Der Test benötigt die aktivierte TapStop-Bedienungshilfe und einen bereits konfigurierten Provider. Für echte Mikrofon-/Provider-Tests weiterhin zwei Diktate in Roh und Plus durchführen. Ein einzelnes modernes Testgerät ersetzt keinen Lauf auf Android 10–12.
