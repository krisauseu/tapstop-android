# Temporäre Transkript-Diagnose — 8. Oktober 2026

**Archiviert am 12. Oktober 2026:** Die temporäre Textaufzeichnung wurde nach der Auswertung aus der App entfernt. Die folgende Beschreibung dokumentiert den ehemaligen Stand.

Anlass: sporadisch ausgelassene Satzteile, aufgefallen bei Umlauten/ß, mindestens einmal mit Local. Der Fehler ist noch nicht mit einem echten betroffenen Diktat reproduziert. Dieser Auftrag baut zunächst die ausdrücklich angeforderte Instrumentierung auf; er ändert weder SDK-Konfiguration, Segmentaggregation, Rewrite-Prompts noch Einfügeverhalten. Die Diagnose bleibt bis zur Auswertung erhalten.

Die Diagnose ist nur in Debug-APKs verfügbar und standardmäßig aus. Auf dem S24 Ultra wird sie für diese Untersuchung ausdrücklich aktiviert. Unter **Einstellungen → Temporäre Transkript-Diagnose** stehen Aktivierung, Export und Löschen. Nach sieben Tagen endet die Aufzeichnung automatisch; erneute Aktivierung beginnt weitere sieben Tage. Abschalten löscht die bisherigen Protokolle nicht. Löschen invalidiert auch spätere Callbacks früherer Diktate.

## Erfasste Grenzen

Alle Ereignisse eines Diktats haben eine UUID (`trace_id`), Sequenznummer und Zeitstempel. Die bisherigen technischen `TapStopDiag`-Ereignisse werden zusätzlich im privaten Protokoll gespeichert.

- Aufnahmebeginn: Provider, Format, lokale 30-Sekunden-Grenze, Gerät und Ziel-Package; am Ende tatsächliche Dauer und Dateigröße. Automatischer Stopp wird separat markiert.
- Konfiguration: STT-Provider/Modell, Modus, Rewrite-Provider und Wörterbuch-Anzahl; keine Wörterbucheinträge oder API-Keys.
- Local: jeder Java-SDK-Callback inklusive vollständigem Text, Sprache, final-Flag und Fehlercode; auch ignorierte/verspätete Callbacks. Anzahl der vorherigen Segmente, Eignung zur Aggregation, `onFinished`, Fehler und zusammengesetztes Transkript.
- Cloud-STT: unveränderter `text`-Wert der Antwort vor Leerprüfung, HTTP-Status und Modell.
- STT-Ergebnis, Rewrite-Eingabe und vollständiger Rewrite-Antworttext vor `trim()`, einschließlich `finish_reason` und Antwortmodell.
- Fertiger Text, exakter an die Einfügung übergebener Text inklusive nachgestelltem Leerzeichen, Einfüge-Ergebnis mit bestehender Insert-Diagnostik und exakt geschriebener Zwischenablage-Text. Rohtext-Fallbacks bei Rewrite-Fehler/Timeout und verworfene Ergebnis-Callbacks werden erfasst.

Text wird unverändert als UTF-8-JSONL gespeichert. Zeilenumbrüche, Anführungszeichen und NUL-Zeichen sind JSON-escaped; nach dem JSON-Decodieren ist der Originalstring wiederhergestellt. UTF-16-Länge, UTF-8-Byteanzahl, Codepoint-Anzahl, NUL und U+FFFD werden zusätzlich gezählt. Es werden keine weiteren Eingabefeld-Inhalte und keine Audiodateien gesammelt. `delivery_result` beschreibt den vorhandenen Einfüge-Erfolgsstatus; bei nicht bestätigten Editoren ist es kein Beweis für den später sichtbaren Text.

## Speicher und Auslesen

Privater, von Android-Backups ausgeschlossener Speicher: `no_backup/transcript-diagnostics/*.jsonl`. Kein Text in Logcat, keine neue Netzwerkübertragung. Eine eigene Preferences-Datei enthält nur das Ablaufdatum; `settings`, Keystore, Modellpakete und `usage.db` bleiben unverändert.

Dateiarbeit läuft auf einem eigenen Worker. Vier Dateien mit je maximal 16 MiB begrenzen den Speicher auf 64 MiB; älteste Dateien werden ersetzt. Ganze Dateien mit letzter Änderung vor mehr als sieben Tagen werden bei Initialisierung, Schreiben und Export bereinigt. Es gibt keinen Hintergrundjob zur Löschung bei geschlossener App. Überlange einzelne Einträge (>16 MiB), volle Queue (512 Jobs) und Schreibfehler werden als technische Fehler in Logcat markiert, ohne die Spracheingabe zu unterbrechen. Text wird niemals still abgeschnitten.

Export über den Dateidialog oder am angeschlossenen S24 Ultra:

```sh
python3 scripts/transcript-diagnostics.py --serial "$ANDROID_SERIAL"
```

Das Skript legt die vollständigen Texte unter dem git-ignorierten Ordner `diagnostics/transcripts` mit Dateirechten 0600 ab. Es zeigt im Terminal nur Zeiten, Provider und Textlängen. Export aus der App auswerten:

```sh
python3 scripts/transcript-diagnostics.py --from-file /pfad/TapStop-Transkript-Diagnose.jsonl
```

Bei einem neuen Aussetzer ungefähre Uhrzeit, gewollten Satz und Ziel-App notieren. Im Protokoll vergleichen: Local-Callbacks → Assembly → STT-Ergebnis → Rewrite-Antwort → Ausgabe. Fehlt der Satz bereits im SDK-Callback, liegt die Grenze vor der App-Aggregation. Fehlt er erst in der Assembly, im Rewrite oder bei der Übergabe, ist diese Grenze gezielt reproduzierbar. `finish_reason=length` belegt eine Begrenzung der Rewrite-Antwort. Ein vollständig wirkendes Transkript allein beweist nicht, dass alles Gesprochene erkannt wurde; für die lokale Modell-/VAD-Analyse brauchen wir danach ein gezieltes reproduzierbares Diktat.
