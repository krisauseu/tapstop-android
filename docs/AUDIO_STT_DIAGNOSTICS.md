# Originalaudio- und STT-Diagnose

## Tatsächlicher Datenfluss

`AudioRecording.create()` wählt anhand des beim Aufnahmestart festgelegten Providers:

- Cloud (Groq, OpenAI und Gemini): eine `MediaRecorder`-Instanz, Mikrofonquelle `MIC`, AAC mit angeforderten 128 kbit/s und 44.100 Hz, MPEG-4-Container, `cache/recording-*.m4a`. Die Kanalzahl wird produktiv nicht gesetzt; die Diagnose liest die tatsächliche Kanalzahl und Samplefrequenz aus der Kopie, statt Mono nur anzunehmen. `stop()` finalisiert den Container, danach folgt `release()`.
- Qualcomm: eine `AudioRecord`-Instanz, PCM16 / Mono / 16.000 Hz, fortlaufend in `cache/recording-*.wav` mit exakt 44 Byte WAV-Header. Nach Stop wird der Header aktualisiert. Die bestehende Grenze von 30 Sekunden bleibt unverändert. Die WAV-Datei geht direkt an die lokale Runtime. Kein neuer PCM-Probe, keine zweite Mikrofoninstanz und keine zusätzliche Pufferverarbeitung.

`OverlayService.finishRecording()` übergibt dieselbe finalisierte Datei an `DictationProcessor.process()` und damit an den ausgewählten STT-Provider. Es gibt keine Umwandlung und keine weitere produktive Audiodatei. `ApiClient.transcribe()` schreibt die Cloud-Datei unverändert in den vorhandenen Multipart-Dateiteil (`speech.m4a`, `audio/mp4`). Gemini verarbeitet dieselbe M4A-Datei per Inline-Request oder Files API. Lokale Qualcomm-Erkennung und Roh-/Rewrite-Auswahl bleiben unverändert; es wird kein Chunking hinzugefügt.

## Kopie und Byteidentität

Unmittelbar nach erfolgreichem Abschluss der Aufnahme, **vor** Provider-Erzeugung und STT-Anfrage, öffnet die Diagnose einen eigenen lesenden `FileInputStream` derselben Eingabedatei. Dieser Dateideskriptor hält unter Android/Linux den ursprünglichen Inode auch dann lesbar, wenn die bisherige Cache-Bereinigung den Dateinamen entfernt. Die produktive Datei wird weder umbenannt noch zurückgehalten oder verändert. Auch Watchdog und `finally { file.delete() }` behalten ihre bisherige Semantik.

Nur das Öffnen und Einreihen erfolgt auf diesem Aufrufpfad. Ein eigener einzelner Writer kopiert mit einem 64-KiB-Puffer, berechnet SHA-256, synchronisiert die Kopie und benennt die `.part`-Datei atomar um. Kein Umkodieren, Resampling, Normalisieren, Abspielen oder zusätzlicher STT-Aufruf. Die STT-Verarbeitung wartet nicht auf das Schreiben. Formatinspektion mit `MediaExtractor` erfolgt ausschließlich auf der fertigen Diagnosekopie.

Bei aktivierter Audiodiagnose berechnet ein `DigestInputStream` zusätzlich während des **bereits vorhandenen** Cloud-Uploads SHA-256 der gelesenen Dateibytes; es gibt dafür keinen weiteren Lesedurchlauf. `STT_UPLOAD_FILE` enthält Hash und Dateigröße. Der Export vergleicht Kopienhash, exportierte Bytes und, soweit vorhanden, diesen Uploadhash. Der Hash bestätigt die dem HTTP-Outputstream übergebenen Dateibytes, nicht die Annahme oder Verarbeitung auf Groqs Server. Bei einem frühen Netzwerkfehler fehlt gegebenenfalls der Uploadhash; die abgeschlossene Eingabedatei wird trotzdem unabhängig gesichert.

## IDs, vollständige Texte und Ereignisse

Bei aktivierter Audiooption entsteht pro Aufnahme eine zufällige UUID `diagnostic_id`. `case.json`, Schema **1**, enthält diese ID, Startzeit, Markierungsstatus, Format, Größe, Hash und die Ereignisse dieses Vorgangs. Der Dateispeicher ist getrennt von den älteren JSONL-Schemas. Frühere IDs werden nicht geändert.

Wichtige Ereignisse:

| Ereignis | Bedeutung |
| --- | --- |
| `RECORDING_START` | Diagnosebeginn unmittelbar vor `recorder.start()`; enthält auch dessen Vorbereitungslaufzeit. |
| `RECORDER_STARTED` | Rückkehr des produktiven Recorder-Starts; Beginn der eigentlichen Aufnahme liegt innerhalb dieses Startaufrufs. |
| `RECORDING_END` | Erfolgreich finalisierte Aufnahme; Provider, Modell, Recorder-Dauer und monotone seit Diagnosebeginn vergangene Zeit. |
| `AUDIO_COPY_PENDING` | Manifest vor dem Kopieren vorhanden; ein abgebrochener Prozess kann hier ohne fertiges Audio enden. |
| `STT_UPLOAD_FILE` | Vollständig in den HTTP-Dateiteil geschriebene Audiodatei, SHA-256 und Größe. |
| `STT_RESULT` | Vollständiger, unveränderter `rawTranscript`-String sowie tatsächlicher Provider, Modell und STT-Laufzeit. |
| `PROCESSED_TEXT` | Vollständiger fertiger Text nach bestehendem Roh-/Rewrite-Pfad. |
| `INSERT_LINK` | Vorhandene Insert-ID und tatsächliches Ergebnis einschließlich Clipboard. |
| `MANUAL_ERROR_MARKER` | Markierungszeit, Zuordnung, gegebenenfalls Insert-ID; kein Screenshotzugriff. |
| `PROCESSING_FAILED` / `PROCESSING_TIMEOUT` | Fehlerklasse ohne Exception-Nachricht beziehungsweise bestehender Watchdog. |
| `RECORDING_FAILED`, `RECORDING_OR_SETUP_FAILED`, `RECORDING_ABORTED_SERVICE_DESTROYED` | Kein behauptetes erfolgreiches STT-Audio für abgebrochene/fehlgeschlagene Aufnahmen. |
| `AUDIO_COPY_FAILED`, `AUDIO_COPY_OPEN_FAILED`, `AUDIO_COPY_QUEUE_FULL` | Diagnoseproblem; produktives Diktat darf weiterlaufen. |

Alle Ereignisse enthalten lokale Unix-Wandzeit in Millisekunden. `recording_duration_ms` übernimmt die vorhandene Recorder-Messung: Cloud monotone Start-/Stop-Zeit, PCM die Zahl erfasster Samples. Zusätzlich enthält `format.media_duration_us` die aus dem Container gelesene Mediendauer beziehungsweise PCM-Sampledauer. Vorbereitungs-, Stop- und Containerdauer können voneinander abweichen. Keine dieser Messungen beweist alleine eine lückenlose Aufnahme.

Die neue Audioaufzeichnung speichert Roh- und Fertigtext unabhängig vom Schalter der Insert-Textdiagnose, ohne deren bisheriges Textlimit zu übernehmen. Sie speichert den unveränderten STT-`text`-Wert, keinen vollständigen HTTP-Body und keine API-Keys oder Wörterbuch-Prompts. Bei Netzwerkfehlern gibt es keinen erfundenen Transkripttext und keine erfundene Insert-ID. Bei Rewritefehlern bleibt das schon empfangene Rohtranskript erhalten; der bisherige Clipboard-Rettungspfad bleibt bestehen.

Bei eingeschalteter Textdiagnose bleiben ihre bisherigen Insert-UUIDs unverändert. `INSERT_BEGIN` erhält optional zusätzlich `diagnostic_id`; `INSERT_LINK.insert_id` verweist auf die gleiche UUID. Über diese ID sind alte Metadaten-JSONL, Prä-Editor, unmittelbare und drei verzögerte Readbacks sowie Marker verknüpfbar. Bei alleiniger Audiodiagnose bekommt ein tatsächlicher Insert eine neue UUID im optionalen bisherigen Metadatenfeld `insert_id`; es werden dadurch keine Editor-Readbacks eingeschaltet.

## Bedienung und Marker

Unter Einstellungen stehen zwei voneinander unabhängige Debug-Schalter:

- **Temporäre Insert-Textdiagnose**: bestehende Editor-/Insert-Beobachtung.
- **Originalaudio für Diagnose speichern**: Originaldatei sowie vollständiges STT-Rohtranskript und fertiger Text.

Für diese Untersuchung sollen beide eingeschaltet sein. Ausschalten stoppt neue Arbeit; vorhandene Dateien bleiben erhalten. Bereits laufende Dateiarbeit kann ihren atomaren Abschluss noch erreichen. Es gibt keine automatische Übertragung und keine Diagnoseoption im Release-Build.

Der normale Ablauf bleibt: Diktat → sichtbarer Fehler → selbst Screenshot erstellen → **„Letzten Einfügefehler markieren“**. Die bestehende Last-Insert-Zuordnung hat Vorrang. Der Audio-Writer sucht genau diese Insert-ID und markiert nur ihren Audiofall. Eine neuere fehlgeschlagene Aufnahme erhält dadurch nicht versehentlich den Marker einer älteren Einfügung. Der bisherige Status `LAST_INSERT_CANDIDATE_UNCONFIRMED`, `STALE_OR_CLOCK_CHANGED` beziehungsweise `NO_INSERT` bleibt erhalten; die Grenze liegt unverändert bei 120 Sekunden. Der Marker ist ein Kandidat und keine Ursachenfeststellung.

Für einen ausdrücklich ausgewählten STT-Fehler ohne Insert gibt es zusätzlich **„Letzte abgeschlossene Aufnahme markieren“**. Diese Aktion bezieht sich auf die separat persistierte letzte abgeschlossene Aufnahme und kennzeichnet den Bezug als `LAST_COMPLETED_RECORDING_CANDIDATE_UNCONFIRMED` beziehungsweise veraltet. Sie überschreibt die alte Last-Insert-Semantik nicht. Beide Aktionen lesen kein Mikrofon und keinen Editor, stoppen keine laufende Aufnahme und löschen keine Diagnosedaten. Die UI sagt bewusst „angefordert“: volle Queue oder Speicherfehler können die Speicherung verhindern.

Ein später vom Nutzer mitgeteilter Screenshotzeitpunkt kann als lokale Exportannotation verknüpft werden; die Originalereignisse bleiben unverändert. Siehe `--screenshot-time` unten.

## Speicherung, Grenzen und Wiederanlauf

Ausschließlich privater App-Speicher:

```
no_backup/audio-stt-diagnostics/<DIAGNOSTIC-UUID>/
  case.json
  input.m4a  # oder input.wav
```

Eigene Preferences `audio-stt-diagnostics.xml` enthalten nur Schalter und letzte Vorgangs-ID/Zeit. Kein Zugriff auf produktive Settings, Keys, Wörterbuch, Modelle oder `usage.db`. Die App deaktiviert Android-Backup; ihre bestehenden Transferregeln schließen private Daten zusätzlich aus. `no_backup` wird von Android nicht gesichert.

Der neue Ordner besitzt ein gemeinsames Budget von **500 MiB für Audio und Metadaten**, einschließlich benötigter temporärer Schreibkopien, und höchstens 4.096 Fälle. Der Writer reserviert vor einer Audiokopie Dateigröße plus Metadatenreserve; übergroße Einzelaufnahmen werden abgelehnt. Vor atomaren Manifestwrites wird zusätzlich deren Platz geprüft. Nur eigene UUID-Unterordner dieses Diagnoseordners werden automatisch gelöscht.

Unmarkierte Fälle werden bei weiterer Diagnosearbeit nach **14 Tagen** gelöscht. Für Kapazitätsbedarf werden ältere unmarkierte Fälle zuerst entfernt. Markierte Fälle werden automatisch weder altersbedingt noch als Kapazitätsopfer entfernt. Reicht der verbleibende Platz nicht, entfällt neue Diagnosearbeit; das Diktat selbst läuft weiter. Auch markierte Fälle haben deshalb keine unbegrenzte Erweiterungsgarantie. Bei inaktiver App gibt es keine zeitgesteuerte Alterslöschung. Der unabhängige ältere Text-Ringpuffer behält seine bisherigen Regeln; eine Audiomarkierung pinnt dessen Readback-Zeilen nicht. Roh-/Fertigtexte und Insert-Link bleiben im Audiofall selbst, solange er aufbewahrt wird.

`AtomicFile` schützt Manifeständerungen; die fertige Audiodatei wird erst nach Sync veröffentlicht und anschließend im Manifest referenziert. Beim nächsten Writerzugriff werden AtomicFile-Backups gelesen, unvollständige `.part`-Dateien und nicht referenzierte Audiodateien entfernt. Ein Prozessende vor Abschluss kann trotzdem einen Vorgang ohne vollständige Kopie oder spätere Events hinterlassen. Fehlende Events sind keine stillschweigende Erfolgsbehauptung. Hardware-/Dateisystemdefekte, voller Datenträger und Force-stop können nicht verlustfrei abgefangen werden.

Ein Worker mit Queue für höchstens 16 Aufgaben begrenzt parallele Diagnosearbeit und offene Quelldeskriptoren. Bei Sättigung werden ausschließlich Diagnoseaufgaben verworfen; Dateischreibfehler melden feste textfreie Logcat-Marker. Es gibt keine Rückkopplung auf produktive Ergebnisentscheidungen. Zusätzliche CPU-/IO-Last der Diagnose bleibt als Messbeeinflussung möglich, obwohl langsame Dateischreibvorgänge außerhalb des produktiven Ablaufs liegen.

## Lokaler Export

Das Exportskript verlangt die erwartete ADB-Seriennummer und das Produktmodell.
Ohne `--output` listet es Fallmetadaten. Mit `--output` schreibt es die
ausgewählten privaten Fälle in ein neues lokales Verzeichnis.

```sh
python3 scripts/audio-stt-diagnostics.py --serial "$ANDROID_SERIAL" --model "$ANDROID_MODEL"
python3 scripts/audio-stt-diagnostics.py --serial "$ANDROID_SERIAL" --model "$ANDROID_MODEL" --marked --output diagnostics/marked-audio
```

Zusätzliche Filter sind `--date` (Europe/Berlin), `--insert-id` und
`--diagnostic-id`. `--screenshot-time` ergänzt bei einem ausgewählten Fall eine
manuelle Zeitangabe ohne bestätigte Zuordnung. Exporte enthalten sensible Audio-
und Textdaten und bleiben lokal.
