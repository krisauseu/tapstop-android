# Verzögerte Textdiagnose

## Aufzeichnung und Bedienung

Unter **Einstellungen → Temporäre Insert-Textdiagnose** gibt es einen Schalter und **„Letzten Einfügefehler markieren“**. In neuen Installationen ist die Textaufzeichnung aus; sie existiert nur in Debug-Builds. Für die ausdrücklich autorisierten Tests und die anschließende Alltagsnutzung wurde sie auf beiden Geräten eingeschaltet. Ausschalten stoppt neue Textdatensätze und Nachkontrollen, bewahrt bereits gespeicherte Daten. Die ältere textfreie Insert-Diagnostik läuft unverändert separat weiter.

Pro protokollierter `deliver()`-Einfügung:

- `INSERT_BEGIN`: UUID, vollständiges STT-Rohtranskript, fertiger Text nach optionalem Rewrite, tatsächliches Insert-Argument einschließlich des bestehenden abschließenden Leerzeichens. Das Rohtranskript ist der unveränderte `text`-Wert der STT-Antwort, kein HTTP-Body. Synthetische Aufrufe ohne STT kennzeichnen fehlendes Rohtranskript mit `raw_available=false`.
- `PRE_INSERT_EDITOR`: bestehender Accessibility-Snapshot vor der Einfügung, Auswahl und Scope. Dieser Snapshot ist **kein garantierter vollständiger Editorinhalt**. Bei Passwortfeldern wird der Editortext ausgelassen.
- `IMMEDIATE_READBACK`: die **bereits vorhandene** Antwort von `getSurroundingText(text.length, 0, 0)`, einschließlich Text, relativem Auswahlbereich und absolutem Offset. `IMMEDIATE_CONFIRMED` entspricht der bestehenden erfolgreichen Insert-Bestätigung. Der InputConnection-Pfad übergibt exakt das aufgezeichnete Insert-Argument an `commitText()`; die Schritte der separaten Metadaten-Datei zeigen, ob der Commit tatsächlich aufgerufen wurde.
- `DELAYED_READBACK`: zusätzliche lesende Abfragen bei nominal 250, 1.000 und 3.000 ms nach Rückkehr des Commits; tatsächlicher Start, Ende und Dauer der Abfrage werden separat gespeichert. Nicht prüfbare Situationen enthalten Status/Grund und keinen fremden Editortext.
- `INSERT_RESULT`: tatsächliches Ergebnis (InputConnection, Accessibility, Clipboard usw.). Keine Änderung an Ergebnisentscheidung oder Clipboard-Inhalt.
- `MANUAL_ERROR_MARKER`: Zeit, Hersteller/Modell, letzte protokollierte Insert-ID, Package, Einfügepfad und Alter der Zuordnung. Kein Text, Screenshot oder automatischer Screenshot-Zugriff.

Insert-/Readback-Zeilen enthalten Schema 2, Zeitstempel, Insert-ID, Gerätebezeichnung, Ziel-Package soweit zu diesem Zeitpunkt bekannt, Pfad, UTF-16-Länge, Umlaut-/ß-Vorkommen und bisherige unmittelbare Bestätigung. Marker enthalten statt Textmerkmalen nur ihre Zuordnung; `insert_timestamp_ms` bezieht sich auf die zugeordnete protokollierte Einfügung. Frühe Ereignisse können noch `UNKNOWN`/`null` enthalten. Modus, Provider, Modell, Aufnahmedauer und finale Wortzahl bleiben in der bestehenden `insert-diagnostics.jsonl`; deren Einträge erhalten bei aktiver Textdiagnose dieselbe `insert_id`. Das trennt Textprotokolle und bisherige Metadatenhistorie und ermöglicht die eindeutige Verbindung beider Dateien.

Rewrite-Fehler-/Watchdog-Kopien des Rohtranskripts, die `deliver()` nicht durchlaufen, sind wie bisher **keine Insert-Vorgänge**. Ihr späteres manuelles Clipboard-Einfügen wird nicht beobachtet.

## Technische Zuordnung und Threading

Androids [AccessibilityInputConnection](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection#getSurroundingText(int,int,int)) stellt einen lesenden IPC-Aufruf bereit. Er kann blockieren und `null` für nicht unterstützte, ungültige oder zu langsame Verbindungen liefern. Die Dokumentation verspricht keinen vollständigen Editorinhalt.

Der [Android-Quellcode von InputMethod](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/accessibilityservice/InputMethod.java) zeigt, dass `currentInputConnection` jedes Mal einen neuen Wrapper erzeugt. Referenzgleichheit dieser Wrapper identifiziert daher keine Sitzung. Eine Unterklasse von `InputMethod` beobachtet stattdessen `onStartInput` (auch Restart) und `onFinishInput`, ruft die jeweiligen Basismethoden auf und erhöht eine Sitzungsgeneration. Fokus-/Fensterereignisse erhöhen eine weitere Generation; jeder Insert erhöht eine Einfügegeneration, auch wenn die Textdiagnose ausgeschaltet ist.

Die Diagnose merkt UUID, Sitzung, Fokusgeneration und Einfügegeneration des ursprünglichen Vorgangs. Timer halten **keine alte Connection** offen. Nach Prüfung der Generationen und des Editor-Packages wird ausschließlich die dann aktuelle Connection für einen einzelnen Readback übernommen. Der Worker besitzt eine eigene Node-Snapshot-Kopie; sein `refresh()` verändert daher keinen von `lastFocused` oder dem produktiven Main-Thread verwendeten Snapshot. Er prüft die ursprüngliche Accessibility-Node-Identität gegen den tatsächlich fokussierten Node, `refresh()` und Fokus vor/nach dem IPC. Sitzungs-/Fokus-/Insertgeneration werden erneut geprüft, anschließend auf dem Main-Thread nochmals validiert, nachdem zwischenzeitliche Android-Callbacks verarbeitet werden konnten. Ein währenddessen unzuordenbar gewordenes Readback wird verworfen und enthält nur `discarded_unattributable_readback=true`.

Es gibt keine Cursorbewegung, Auswahlaktion, keinen Commit, `ACTION_SET_TEXT` oder Retry in den neuen Nachkontrollen. Produktive Timeouts und der Clipboard-Fallback bleiben unverändert. Die bereits vorhandenen Cursor-Reparaturen des Accessibility-Einfügepfads bleiben unverändert.

IPC-Abfragen und Zeichenvergleich laufen auf einem einzelnen Hintergrundworker mit höchstens drei wartenden Aufgaben. Dateiarbeit/JSON-Serialisierung laufen auf einem separaten Writer mit höchstens 16 wartenden Ereignissen. Sättigung verwirft ausschließlich Diagnosearbeit; Reader-Sättigung erhält einen nachvollziehbaren Grund. Writer-Sättigung/Schreibfehler melden nur feste textfreie Logcat-Marker. Ein langsamer Readback kann nachfolgende Prüfungen verschieben: die gemessenen Zeiten, nicht das nominale Timerlabel, sind für die Auswertung maßgeblich. Die bestehende synchrone unmittelbare Erfolgsprüfung wurde nicht erweitert. Eigene App-Editoren werden weiterhin ohne synchrone Rückfrage eingefügt.

## Vergleich, Aussagekraft und Grenzen

[SurroundingText](https://developer.android.com/reference/android/view/inputmethod/SurroundingText) liefert Auswahlpositionen innerhalb seines Ausschnitts und einen absoluten Offset; `-1` bedeutet unbekannter Offset. Aus der unmittelbaren Bestätigung wird der Anfang des eingefügten Bereichs berechnet: `offset + selectionStart - insertArgument.length`. Spätere Abfragen verlangen jeweils bis zu 65.536 UTF-16-Einheiten vor und nach der aktuellen Auswahl. Die Auswahl darf sich bewegen; sie wird niemals aktiv verändert.

Verglichen wird der eingefügte Bereich an seiner ursprünglichen absoluten Position, nicht die Gesamtlänge. Bestehender Text davor und danach wird nicht mit dem Diktat verwechselt. Wo beide Snapshots davor Text enthalten, muss der gemeinsame Präfix übereinstimmen. Bei geändertem Präfix ist die Zuordnung der Positionen unsicher. Ein bestätigter späterer Ausschnitt kann den Präfixanker für weitere Prüfungen erweitern. Fehlt für eine Abweichung bei einem nicht bei Position 0 beginnenden Insert ein Präfixanker, bleibt sie nicht überprüfbar.

| Status | Bedeutung |
| --- | --- |
| `IMMEDIATE_CONFIRMED` / `IMMEDIATE_UNCONFIRMED` | Bestehender unmittelbarer Vergleich erfolgreich / nicht erfolgreich. |
| `DELAYED_CONFIRMED` | Der ganze ursprüngliche Insert-Bereich ist im zugeordneten Ausschnitt zeichengetreu enthalten. Keine Aussage über andere Bereiche oder Darstellung. |
| `DELAYED_TEXT_MISMATCH` | Im zugeordneten und positionell prüfbaren Bereich wurde eine tatsächliche Zeichenabweichung beobachtet. Urheber unbekannt, **kein Nachweis eines TapStop-Fehlers**. |
| `DELAYED_TARGET_CHANGED` | Neue Einfügung, andere Eingabesitzung, Fokus-/Fensterereignis oder anderes fokussiertes Feld macht eine Bewertung für den ursprünglichen Vorgang unzulässig. |
| `DELAYED_FOCUS_LOST` | Originalfeld verlor seinen Fokus vor oder während der Abfrage. |
| `DELAYED_CONNECTION_UNAVAILABLE` | Keine Connection oder `null`-Readback; Protokoll unterscheidet nicht spekulativ zwischen Timeout, Invalidität und fehlender Unterstützung. |
| `DELAYED_NOT_VERIFIABLE` | Zum Beispiel unbekannter Offset, fehlender Positions-/Präfixanker, zu kurzer Ausschnitt, geänderter Präfix, nicht vorhandene Sitzungsidentität, Passwort-/Größenlimit, zerstörter Dienst oder voller Reader. |

Die Abweichung enthält erste nachgewiesene abweichende UTF-16-Position, geprüfte Bereichslänge, ursprüngliche Insert-Länge, Umlaut-/ß-Merkmal, unmittelbaren Bestätigungsstatus und tatsächliche Messzeitpunkte. Eine Länge des „fehlenden“ Bereichs wird bewusst nicht erfunden: Ein kurzer Rückleseausschnitt allein beweist keine Löschung. Eine genaue Verlust-/Ersetzungsstrecke kann später anhand vollständiger, ausreichend überlappender Textstände untersucht werden. Java-Zeichenindizes sind UTF-16-Einheiten, keine Glyphen; Emoji können zwei Einheiten haben. Die Texte werden weder normalisiert noch Unicode-ersetzt.

**Android meldet den Urheber einer Textänderung nicht.** Tastatureingabe, manuelle Korrektur, IME-Autokorrektur und App-Verarbeitung lassen sich aus Readbacks nicht sicher auseinanderhalten. Auch bei stabiler Sitzung ist eine Abweichung deshalb mit `cause=UNKNOWN`, `proven_tapstop_text_loss=false` und dem Grund `OBSERVED_DIFFERENCE_MANUAL_IME_OR_APP_CHANGE_POSSIBLE` versehen. Der Bericht gibt keine unbestätigten Verdachtsfälle als bewiesenen Textverlust aus. Manueller Screenshot und Marker liefern zusätzliche zeitliche Hinweise.

Die API meldet Ausschnitte. **Jedes Editor-Textobjekt trägt `scope=SURROUNDING_TEXT_WINDOW` und `whole_editor_verified=false`.** Ein vollständig bestätigter Insert-Bereich ist keine Behauptung, dass der gesamte Editor erfasst wurde. Größere Rückgaben werden vollständig ausgelassen und mit Limitgrund gekennzeichnet. Accessibility- und Clipboard-Pfade haben keine unmittelbar bestätigte InputConnection als Basis; ihre drei Nachkontroll-Einträge sind ausdrücklich `NO_CONFIRMED_INPUT_CONNECTION_PATH` (ohne zusätzliche IPC-Abfragen, bereits bei Abschluss geschrieben). Der eigene-App-Pfad bleibt `INPUT_CONNECTION_OWN_UNVERIFIED`.

Weitere Grenzen: Passwortfelder, fehlende/verspätete Framework-Ereignisse, kaputte Editorimplementierungen, kurzzeitige Fokuswechsel, Fensterwechsel durch System-UI, Prozessende, Ringpufferverdrängung und volle Queues können Nachkontrollen verhindern. Konservative Ablehnungen sind erwartbar. Die geringe zusätzliche IPC-Last kann Editorarbeit beeinflussen; absolute Nichtbeeinflussung fremder Editorprozesse ist nicht beweisbar. Deshalb werden Laufzeiten erfasst und Abfragen begrenzt. Es wird kein Bildschirmzustand und kein später abgesendeter Nachrichtentext geprüft.

## Lokale Speicherung und Rückbau

Private, von Backup und Gerätetransfer ausgeschlossene Dateien:

```
/data/user/0/de.kf.blitztext/no_backup/insert-delayed-diagnostics/events-*.jsonl
```

Abgebrochene AtomicFile-Schreibreste werden beim nächsten Schreiben bereinigt, vorhandene AtomicFile-Backups wiederhergestellt. 16 atomisch geschriebene Segmente zu höchstens 2 MiB, zusammen höchstens **32 MiB**, zuzüglich einer vorübergehenden atomaren Schreibkopie von höchstens 2 MiB. Pro Segment höchstens 4.096 Ereignisse. Im aktiven Segment werden beim Schreiben Ereignisse älter als **14 Tage** entfernt. Geschlossene Segmente werden beim nächsten Schreiben gelöscht, sobald ihr letzter Schreibzeitpunkt mehr als 14 Tage zurückliegt; einzelne ältere Ereignisse darin können bis zur Segmentbereinigung erhalten bleiben. Zusätzlich verdrängt die 32-MiB-Grenze ältere Segmente. Bei einer nicht mehr schreibenden App gibt es keine zeitgesteuerte Löschung. Ausschalten löscht nichts. Ein einzelner Textstand über 65.536 UTF-16-Einheiten wird ganz ausgelassen und mit `*_omitted` gekennzeichnet; Editor-Rückgaben über 131.072 ebenso. Die üblichen Alltagsdiktate werden vollständig gespeichert; mehrtägige Erhaltung hängt von ihrem Umfang ab, ist wegen des Ringpuffers keine Garantie.

Keine Audiodaten, Konfigurations-API-Keys, Prompt-/Wörterbuchinhalte oder Exception-Nachrichten werden vom Recorder abgefragt. Diktat- und Editorinhalte sind bei aktivierter Diagnose ausdrücklich Klartext in den privaten Textdateien; das ist die vom Nutzer autorisierte Ausnahme. Marker-Preferences enthalten nur ID, Zeit, Package und Pfad. Die vorhandenen Statistiken bleiben textfrei und `usage.db` unverändert. Es gibt keine zusätzlichen Uploads, Cloud-Synchronisation oder automatische Screenshot-Übertragung. Der Geräte-Smoke-Test verwendet ein lokales `data:`-Dokument ohne STT-/Netzwerkanfrage.

Nach Abschluss der Fehlersuche: Diagnose ausschalten, benötigte Dateien gezielt lokal exportieren, dann Recorder, Callback-/Insert-/Overlay-Hooks, Diagnose-UI und ausschließlich `no_backup/insert-delayed-diagnostics` samt `shared_prefs/insert-delayed-diagnostics.xml` entfernen. Das muss als gesonderter Rückbau erfolgen; dieses Update löscht keine vorhandene Historie. `settings.xml`, Wörterbuch, Keys, `usage.db` und ältere Insert-Diagnosedaten dürfen dabei nicht gelöscht werden.

## Fehlermarker

Bei sichtbarem Fehler zuerst selbst einen Screenshot erstellen; anschließend TapStop öffnen und in den Einstellungen **„Letzten Einfügefehler markieren“** drücken. Die Aktion liest keinen Editor und keinen Screenshot. Sie funktioniert lokal/offline auch nach abgeschlossenem Insert und Prozessneustart.

Die letzte **protokollierte** Einfügung wird als Kandidat gespeichert, nicht als sicher festgestellte Fehlerursache. Innerhalb von 120 Sekunden: `LAST_INSERT_CANDIDATE_UNCONFIRMED`; danach oder bei rückwärts verstellter Uhr: `STALE_OR_CLOCK_CHANGED`; ohne bisherigen Insert: `NO_INSERT`. Zwischenzeitliche nicht protokollierte Einfügungen kann der Marker nicht rekonstruieren. Die UI sagt deshalb „Marker angefordert“ und zeigt ID-Anfang sowie Zuordnung; ein voller Writer oder Speicherfehler kann das Schreiben verhindern. Die Auswertung prüft zusätzlich, ob die zugeordnete Insert-ID noch im Ringpuffer vorhanden ist. Testmarker sind echte lokal gespeicherte Marker, aber keine nachgewiesenen Fehler.

## Auslesen beider Geräte

Das neue Skript identifiziert angeschlossene Geräte und prüft Modell/Serial. Bei mehreren Geräten ist `--serial` Pflicht; auch bei nur einem Gerät wird jede nachfolgende ADB-Abfrage mit explizitem `-s` ausgeführt. Exportiert wird ausschließlich der neue Diagnoseordner, keine Preferences, Keys, Audiodaten oder Statistikdaten. JSONL-Exports erhalten Rechte 0600 und überschreiben keine bestehenden Dateien. Das Terminal zeigt nur Metadaten, keine Texte. `diagnostics/` ist git-ignoriert.

Samsung Galaxy S24 Ultra (`SM-S928B`):

```sh
python3 scripts/delayed-insert-diagnostics.py --serial "$SAMSUNG_SERIAL"
python3 scripts/delayed-insert-diagnostics.py --serial "$SAMSUNG_SERIAL" --output "diagnostics/samsung-$(date +%Y%m%d-%H%M%S).jsonl" --json
```

HONOR MagicPad (`YLE-W09`):

```sh
python3 scripts/delayed-insert-diagnostics.py --serial "$HONOR_SERIAL"
python3 scripts/delayed-insert-diagnostics.py --serial "$HONOR_SERIAL" --output "diagnostics/honor-$(date +%Y%m%d-%H%M%S).jsonl" --json
```

Offline-Auswertung:

```sh
python3 scripts/delayed-insert-diagnostics.py --file diagnostics/DATEI.jsonl --json
```

Die vorhandenen Metadaten bei Bedarf getrennt auslesen:

```sh
python3 scripts/insert-diagnostics.py --serial "$SAMSUNG_SERIAL" --output diagnostics/samsung-insert-metadata.jsonl
python3 scripts/insert-diagnostics.py --serial "$HONOR_SERIAL" --output diagnostics/honor-insert-metadata.jsonl
```

Berichte zählen noch gespeicherte abgeschlossene Inserts, InputConnection-Erfolge, separat unbestätigt angenommene Commits, Accessibility, Clipboard, unmittelbare Bestätigungen, verzögerte Statusverteilungen je Timer, Gründe nicht prüfbarer Kontrollen, Ziel-App- und Längenverteilung sowie Marker mit Insert-ID. Fehlende Nachkontroll-Ereignisse werden separat gezählt. Bei Ringpufferverdrängung/Prozessende kann eine Sitzung unvollständig sein; Summen sind keine lebenslange Gesamtzahl. `proven_tapstop_text_losses` bleibt 0, da diese Instrumentierung keine Urheberzuordnung beweisen kann.
