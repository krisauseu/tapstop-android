# Temporäre Insert-Diagnostik — 6. Oktober 2026

Diese Debug-Instrumentierung beobachtet den bestehenden Einfügeweg. Sie verändert weder Fokusauswahl, Rückgabewerte, Commit/ACTION_SET_TEXT, Retries, Delays, Timeouts noch Clipboard-Inhalt. Aufnahme, STT, Rewrite, Chunking und `usage.db` bleiben unverändert.

## Entscheidungskette vor der Instrumentierung

`rememberFocusedField()` merkt das fokussierte editierbare Feld: aktives Fenster → fokussierte/aktive Accessibility-Fenster → noch gültiges `lastFocused`. Beim fertigen Ergebnis ergänzt `OverlayService.deliver()` wie bisher gegebenenfalls ein Leerzeichen.

`TextInsertService.insert()` verbraucht das gemerkte Ziel. Fehlendes Ziel oder ein bei `refresh()` nicht mehr gültiges/editierbares/fokussiertes Ziel ergibt `false`. Der alte Text und die Auswahl bilden den bestehenden Snapshot für ACTION_SET_TEXT.

Ab API 33: InputConnection muss vorhanden sein und das Editor-Package zum Ziel passen. Beim eigenen App-Package wird `commitText` abgeschickt und unmittelbar als erfolgreich behandelt. Fremde Editoren werden zuerst mit `getSurroundingText(0,0,0)` geprüft. Nach dem Commit prüft eine zweite Abfrage, ob vor einer zusammengefallenen Auswahl der eingefügte Text steht. Eine fehlende Bestätigung oder Exception ergibt `false` für diesen Teilweg und verhindert den Accessibility-Versuch nicht. Es gibt keinen eigenen Insert-Timeout; ein `null` der Framework-Abfrage beweist keinen bestimmten Timeout-Grund.

Nach erfolglosem InputConnection-Teilweg (bzw. vor API 33): Ziel erneut prüfen → ACTION_SET_TEXT mit demselben Snapshot. Ein `false` ergibt Insert-Fehlschlag. Bei Erfolg folgen die bestehenden Cursoraktionen und Reparaturen nach 100/450 ms; der Insert liefert `true`. Eine Exception im äußeren Insert liefert `false`.

`deliver()` kopiert bei `false`, fehlendem Dienst oder Exception im Aufrufer den fertigen Text ins Clipboard. Die gesonderten Rohtranskript-Kopien bei Rewrite-Fehler oder Verarbeitungs-Watchdog sind keine Insert-Versuche und werden in dieser Insert-Datei nicht gezählt.

## Aufzeichnung

Pro `deliver()` ein JSONL-Datensatz: `timestamp_ms` (Unixzeit), Ziel-Package soweit bekannt, `text_chars` des tatsächlichen Insert-Arguments **einschließlich** des bisherigen abschließenden Leerzeichens, vorhandene finale Wortzahl, tatsächliche `recording_ms`, Modus (`BLITZTEXT` = Roh, `PLUS`, `CHAT`, `FORMAL`), STT-Provider, lokal/cloud und STT-Modell. Aufnahme-/Konfigurationswerte kommen aus dem bereits verwendeten Diktat-Snapshot. Ein aktuelles Ziel-Package ersetzt das am Aufnahmestart gemerkte Package; ohne Ziel bleibt dieses als letzter bekannter Hinweis erhalten. Bei synthetischen Einargument-Aufrufen fehlen die übrigen Metadaten (`null`).

`steps` ist eine geordnete Liste fester Kategorien, insbesondere Zielprüfung, Connection-Verfügbarkeit, Package-Abgleich, Commit-Versuch, Bestätigung, Accessibility-Versuch und tatsächliches Clipboard-Schreiben. `INPUT_CONNECTION_PROBE_EMPTY` bedeutet vorhandenes Connection-Objekt mit leerer Framework-Antwort; `INPUT_CONNECTION_UNCONFIRMED` bedeutet, dass die bestehende Nachprüfung den Commit nicht bestätigt. Keine dieser Kategorien beweist, dass im Editor keinerlei Text ankam.

Ergebnisse:

- `DIRECT_INPUT_CONNECTION_SUCCESS`: bestehende Nachprüfung bestätigt den Commit.
- `DIRECT_INPUT_CONNECTION_ACCEPTED_UNVERIFIED`: bestehender eigener-App-Pfad ohne synchrone Bestätigung. Separat erkennbar und bei InputConnection-Erfolgen mitgezählt.
- `DIRECT_ACCESSIBILITY_SUCCESS`: bestehender ACTION_SET_TEXT-Pfad liefert Erfolg.
- `CLIPBOARD`: `setPrimaryClip` kehrte tatsächlich zurück; Schritt `CLIPBOARD_WRITTEN`.
- `CLIPBOARD_WRITE_FAILED`: Clipboard war erforderlich, aber kein erfolgreicher Schreibabschluss wurde beobachtet. Ein bereits erfolgreicher Clipboard-Schritt bleibt bei einer späteren Exception als geschrieben erkennbar.

`clipboard_reason` beschreibt den letzten bekannten Fehler (`SERVICE_UNAVAILABLE`, `NO_RETAINED_TARGET`, `INVALID_TARGET`, `ACCESSIBILITY_TARGET_INVALID`, `ACCESSIBILITY_REJECTED`, `INSERT_EXCEPTION`, `DELIVERY_EXCEPTION`). Frühere InputConnection-Ursachen stehen in `steps`. `UNCLASSIFIED` und `DIRECT_SUCCESS_UNCLASSIFIED` sind ausdrücklich unbekannte Gründe, keine vermuteten Ursachen.

Es werden keine Diktattexte, Editor-/Clipboard-Texte, Audio, Schlüssel, Wörterbuch- oder Prompt-Inhalte sowie keine Exception-Nachrichten gespeichert. Die Diagnose-Schnittstelle nimmt nur Zahlen, Package-Metadaten und feste Konfigurations-/Schrittwerte entgegen.

## Speicherung und Grenzen

Nur in Debug-Builds und solange mindestens einer der beiden Diagnoseschalter aktiv ist. Private Datei: `/data/user/0/de.kf.blitztext/no_backup/insert-diagnostics.jsonl`; getrennt von Statistik und Preferences. AtomicFile verhindert teilweise ersetzte Dateien. Ein einzelner Hintergrundwriter arbeitet erst nach der Ergebnisentscheidung; Fehler können keinen erfolgreichen Insert in einen Fallback verwandeln.

Maximal **1.024 Datensätze und 1 MiB** in der fertigen Datei. Beim nächsten Schreiben werden Datensätze über sieben Tagen entfernt; bei ruhender App gibt es keine zeitgesteuerte Löschung. Während des atomaren Schreibens kann zusätzlich eine temporäre Kopie von bis zu 1 MiB bestehen. Maximal 64 ausstehende Schreibaufträge; Überlast verwirft nur Diagnosedaten (`TapStopInsertDiag: queue_full_record_dropped`). Speicherfehler werden ausschließlich als `storage_failed` ohne Inhalte gemeldet. Ein abrupt beendeter Prozess kann noch ausstehende Einträge verlieren. Erfolgreich geschriebene Einträge überstehen den Prozessneustart.

Die Summen beziehen sich auf **noch gespeicherte** Insert-Versuche; nach Ringpuffer-Verdrängung sind sie keine lebenslangen Gesamtzahlen. Die Diagnose liefert Daten und trifft keine Längen-/Ursachenhypothese.

## Spätere Auswertung

Aus dem Repository:

```sh
python3 scripts/insert-diagnostics.py --output diagnostics/insert-diagnostics.jsonl
```

Mit mehreren ADB-Geräten zusätzlich `--serial SERIAL`. Das Skript liest ausschließlich diese Diagnose-Datei und liefert JSON mit Insert-/Pfadzählern, Clipboard-Gründen, Packages, Min/Median/Max von Zeichenlänge und Aufnahmedauer sowie den einzelnen Clipboard-Fällen einschließlich Schrittfolge. Unbekannte Dauern werden separat durch die Anzahl gültiger Messwerte sichtbar.

Eine exportierte Datei erneut auswerten:

```sh
python3 scripts/insert-diagnostics.py --file diagnostics/insert-diagnostics.jsonl
```

Nur Rohdaten lesen:

```sh
adb exec-out run-as de.kf.blitztext cat no_backup/insert-diagnostics.jsonl
```
