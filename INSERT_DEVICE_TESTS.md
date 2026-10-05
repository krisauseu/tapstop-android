# Local-STT und Einfüge-Regression 0.9 — 5. Oktober 2026

**Abgeschlossen:** Direkte Einfügung nach echter lokaler Roh-Spracherkennung sowie Local + Groq/Plus ist auf beiden Geräten vom Nutzer angenommen. Auf dem MagicPad bestand die Plus-Wiederholung mit korrigierter SDK-Fehlerbehandlung; der frühere Versuch mit SDK-Fehlermarker bleibt ausdrücklich nicht abgenommen. Beide Geräte haben denselben endgültigen APK-Stand und bestanden die abschließenden zwölf Browser-Fixtures. Die bestehenden Einfügewege wurden für den Qualcomm-Provider nicht durch gerätespezifische Sonderfälle ersetzt. Die unten beschriebene SDK-Fehlerbedingung nahe 30 Sekunden bleibt bestehen.

## S24: echte lokale Roh-Diktate

Samsung `SM-S928B`, Android API 36; TapStop 0.9 / `versionCode=9` als Update mit erhaltener Application-ID, Signatur und App-Daten. Automatische Zuordnung SM8650 → QNN SoC 57 / HTP V75.

Vier echte Mikrofon-Diktate in Roh wurden mit dem TapStop-eigenen lokalen Modell erfolgreich verarbeitet. Ziel war jeweils ein Chrome-Eingabefeld. Der Nutzer bestätigte korrekte Spracherkennung und direkte Einfügung. Die gespeicherten Metadaten nennen `target_package=com.android.chrome`; `rewrite_ms=NULL` bestätigt für diese Datensätze, dass keine Rewrite-Stufe abgeschlossen wurde. Der Roh-Pfad ohne Rewrite-Aufruf ist zusätzlich automatisiert geprüft.

Die Aufnahmezeiten betrugen 13,200 / 16,480 / 6,020 / 10,480 s; lokale STT benötigte 0,734 / 1,330 / 0,611 / 0,763 s. Native Belege bestätigen für jeden erfolgreichen Lauf Encoder und Decoder über den App-lokalen V75-Skeleton und CDSP/FastRPC. Messwerte, Statistik und die Abgrenzung eines anschließenden sprachlosen Fehlerlaufs stehen in [DEVICE_TESTS.md](DEVICE_TESTS.md).

Diese echte Abnahme bestätigt den vollständigen Weg Mikrofon → lokales Whisper → bestehende Texteingabe. Sie ist keine DOM-/Cursor-Prüfung jeder denkbaren Feldvariante. Die folgenden separaten Tests decken diese Varianten ab.

## S24: echtes Local-Diktat mit Plus-Rewrite

Der Nutzer bestätigte auch das anschließende Plus-Ergebnis als vollständig korrekt. Die Aufnahme endete automatisch mit **29,940 s** tatsächlichem PCM-Audio. Local-STT benötigte **1,397 s**, der anschließende Groq-Rewrite **1,692 s**, die Gesamtverarbeitung ab Stop **3,193 s**. Die Statistik führt das erfolgreiche Diktat weiterhin unter Qualcomm NPU / Whisper Large V3 Turbo, Modus `PLUS`, Ziel `com.android.chrome`.

Die direkte Texteingabe wurde im Browser geprüft: **201 Zeichen, 29 Wörter, Cursorposition 201**. Der Text wurde in das fremde Eingabefeld übernommen; ein manuelles Einfügen aus der Zwischenablage war nicht erforderlich. Der persönliche Wortlaut wird hier nicht gespeichert. Encoder und alle 65 Decoderaufrufe sind am selben belegten V75-/CDSP-Handle nachgewiesen. Damit ist auch der vollständige Weg Mikrofon → Local-STT → konfigurierter Cloud-Rewrite → bestehende Texteingabe auf dem S24 praktisch bestätigt.

## S24: automatisierte Browser- und native Feldtests

Zwölf Chrome-Tests wurden für 0.9 erneut durchgeführt. Je Test wurden Browserinhalt und Cursorposition geprüft; alle zwölf bestanden:

| Fall | Normaler Einfügeweg, InputConnection vorhanden | InputConnection ausdrücklich deaktiviert |
| --- | --- | --- |
| Leeres Input | bestanden | bestanden |
| Textarea mit Placeholder | bestanden | bestanden |
| Vorhandenen Text ergänzen | bestanden | bestanden |
| Cursor mitten im Text | bestanden | bestanden |
| Markierte Auswahl ersetzen | bestanden | bestanden |
| Contenteditable, Cursor mitten im Text | bestanden | bestanden |

Der erzwungene zweite Durchlauf prüft direkte Accessibility-Einfügung; die Bezeichnung `fallback` des Testskripts bedeutet dort **nicht Clipboard-Fallback**. Jeder Lauf meldete erfolgreiche direkte Einfügung. Die Tests verwenden ausschließlich synthetischen Text, führen keine Spracherkennung oder Cloudaufrufe aus und schreiben keine echten Statistikzeilen.

Die separate Geräte-Instrumentierung bestand zusätzlich native InputConnection-Fälle für leere Felder mit/ohne Placeholder, Anhängen und Cursorposition mitten im Text. Der bestehende Clipboard-Fallback ohne verwendbares Ziel wurde getrennt erfolgreich geprüft. Die Testdatenbank ist von der echten Nutzungsstatistik getrennt.

### S24: abschließende Prüfung mit korrigierter SDK-Fehlerbehandlung

Das abschließende Update auf denselben APK-Stand wie das MagicPad erhielt Preferences und Statistikdatenbank byteidentisch, einschließlich 126 vorhandener Zeilen und Schema Version 1. Dieser unveränderte Bestand wurde auch nach sämtlichen abschließenden Browser-Fixtures bestätigt. Eine echte native Fehler-Fixture mit **29.980 ms** Audio bestätigte **0 Rewrite-Aufrufe und 0 Teiltext-Übergaben**; ein gesunder Folgelauf war im selben Prozess ohne erneute Initialisierung erfolgreich. Diese Regression prüft die SDK-Fehlerbehandlung, nicht die Mikrofonqualität oder Browser-Einfügung.

Der neue Browser-Fixture-Lauf scheiterte zunächst im ersten leeren Input mit „Direct insertion failed“, leerem Feld und Cursorposition 0. Die tatsächliche Android-Bedienungshilfe zeigte ausschließlich `com.android.systemui` und keinen fokussierten Eingabeknoten: Der Benachrichtigungsbereich war offen, obwohl CDP das Chrome-Dokument als sichtbar und fokussiert meldete. Der Sperrbildschirm war nicht aktiv. Nach dem Schließen des Benachrichtigungsbereichs bestand der vollständige Wiederholungslauf **alle zwölf Fälle** mit geprüftem Inhalt und Cursor, davon sechs mit verfügbarer und sechs mit ausdrücklich deaktivierter InputConnection. Keine Änderung an der Produktions-Einfügelogik. Der erste fehlgeschlagene Lauf wird nicht als bestanden gezählt; der anschließende vollständige Lauf bildet den Abschlussnachweis.

## MagicPad: erster echter Roh-Durchlauf

HONOR `YLE-W09`, Android API 36, SM8845P → QNN SoC 97 / HTP V81. TapStop wurde von 0.8 auf 0.9 aktualisiert; die 177 vorhandenen Statistikzeilen, Schema Version 1 sowie Preferences blieben über die vorbereitenden synthetischen Regressionen hinweg unverändert.

Das erste echte lokale Roh-Diktat umfasste **20,520 s** Audio, **1,095 s STT** und **1,132 s Gesamtzeit**. Die direkte Chrome-Einfügung wurde technisch geprüft: **104 Zeichen, 22 Wörter, Cursorposition 104**. Die Statistik enthält den neuen erfolgreichen Qualcomm-Datensatz mit Ziel `com.android.chrome`, Modus `BLITZTEXT` und `rewrite_ms=NULL`. Die nativen Belege bestätigen beide internen SDK-Abschnitte auf V81: Encoder 343 / 340 ms und sämtliche 20 / 19 Decoderaufrufe am belegten CDSP-Handle. Kein App-Chunking.

Der Nutzer nahm das Roh-Ergebnis mit einer Eigennamenabweichung an: „Hunora“ statt „Honor“. Damit sind lokale Erkennung und direkte Einfügung für diesen Lauf qualitativ angenommen; eine fehlerfreie Transkription oder allgemeine Wortfehlerrate wird daraus nicht abgeleitet.

## MagicPad: erster Plus-Versuch nicht abgenommen

Der erste echte Plus-Versuch stoppte automatisch nach **29,980 s** Audio und durchlief Local-STT (**981 ms**) sowie Groq-Rewrite (**1.337 ms**, gesamt **2.366 ms**). Trotz bestätigter HTP-V81-Ausführung enthielt das ausgegebene Ergebnis den SDK-Marker `SPECTROGRAM FAIL`. Der Nutzer beanstandete ihn; der Lauf gilt ausdrücklich nicht als erfolgreiches Plus-Diktat. Die vor der Korrektur entstandene Erfolgsmarkierung in der historischen Statistikzeile 179 bleibt erhalten, ist aber kein positiver Abnahmebeleg.

Ursache war ein finaler nativer Callback mit Code `0` und genau diesem Marker als vollständigem Text. Die korrigierte App behandelt ihn als STT-Fehler, verwirft vorherige Teiltexte und startet weder Rewrite noch Ergebnisübergabe an die Einfügelogik. Normale Diktattexte werden nicht nach enthaltenen Wörtern gefiltert. Die native Geräte-Fixture reproduzierte den alten falschen Erfolg zweimal und bestätigte nach der Korrektur **0 Rewrite-Aufrufe und 0 Teiltext-Übergaben**. Ein ausdrücklich neu gestarteter gesunder Lauf gelang anschließend im selben Prozess ohne erneute native Initialisierung.

Der zugrunde liegende SDK-Fehler kann bei Aufnahmen nahe 30 Sekunden weiterhin auftreten; TapStop meldet ihn sichtbar, statt den Marker einzufügen. Ein neues kürzeres Diktat kann danach gestartet werden. Es gibt keine eigene Chunk-/VAD-Umgehung und keinen automatischen Cloud-Fallback. Die folgende erfolgreiche kürzere Wiederholung hebt diese bekannte Grenze nicht auf; weitere Messwerte stehen in [DEVICE_TESTS.md](DEVICE_TESTS.md).

## MagicPad: korrigierter Plus-Durchlauf angenommen

Ein neues echtes Diktat mit **20,060 s** Aufnahme lief mit der korrigierten App erfolgreich durch Local-STT (**737 ms**) und Groq-Rewrite (**1.049 ms**), insgesamt **1.829 ms ab Stop**. Der Nutzer bestätigte das Ergebnis als „Perfekt und ohne Fehler“. Die direkte Chrome-Einfügung ist mit **126 Zeichen, 18 Wörtern und Cursorposition 126** bestätigt. Das Ergebnis wurde direkt in das fremde Feld eingefügt; kein manueller Clipboard-Schritt war erforderlich.

Der native Nachweis bestätigt den V81-Encoder (**338 ms**) und alle **37 Decoderaufrufe** am selben belegten CDSP-Handle. In diesem App-Prozess wurde genau einmal initialisiert. Die erfolgreiche Statistikzeile 180 führt Qualcomm / Whisper Large V3 Turbo, Modus `PLUS` und Ziel `com.android.chrome`. Die frühere falsche Erfolgsmarkierung aus Zeile 179 bleibt als historische Nutzungsdaten erhalten und wird nicht als positiver Qualitätsbeleg gezählt. Damit ist der vollständige Weg Mikrofon → Local-STT → konfigurierter Cloud-Rewrite → direkte Texteingabe auch auf dem MagicPad praktisch abgenommen.

## MagicPad: Browser-Fixtures und Testrunner-Rebind

Der erste Browser-Test für ein leeres Feld scheiterte mit „Direct insertion failed“. Die später gelesene Systemdiagnose zeigte Chrome als aktives und fokussiertes Fenster sowie einen gebundenen TapStop-Dienst; Gboard hatte weder aktiven noch Eingabefokus. Daraus lässt sich der Zustand des Zielknotens genau zum Fehlerzeitpunkt nicht rekonstruieren.

Ein möglicher Race wurde im Harness identifiziert: Nach `READY_ACCESSIBILITY` deaktiviert und aktiviert das Hostskript den Dienst. Der Testrunner konnte zuvor bereits eine Service-Instanz gespeichert haben, die beim anschließenden Rebind ersetzt wurde. Die nativen Feldtests lesen die aktuelle Instanz dagegen später erneut. Diese Erklärung ist ein plausibler Kandidat, kein abschließender Ursachennachweis.

Der **vollständige Wiederholungslauf ohne Änderung der installierten App** bestand alle zwölf Browserfälle der obigen Matrix, jeweils mit bestätigtem Inhalt und Cursor: sechs normale Fälle und sechs mit ausdrücklich deaktivierter InputConnection. Die nativen InputConnection- und Clipboard-Fallback-Tests bestanden ebenfalls.

Eine gezielte Änderung ausschließlich in `BrowserInputChecks.kt` wartet künftig den Rebind vor der Instanzaufnahme ab, prüft die aktuelle Instanz unmittelbar vor Aktionen und restauriert Flags nur für dieselbe weiterhin aktive Instanz. Es gibt keinen automatischen zweiten Einfügeversuch. Der erste erfolgreiche Wiederholungslauf lag vor dieser Absicherung und wird ihr daher nicht zugeschrieben. Ein weiterer vollständiger Lauf mit der neuen Test-APK bestand anschließend ebenfalls **alle zwölf Fälle**, mit geprüftem Inhalt und Cursor sowie bestätigter Verfügbarkeit beziehungsweise Deaktivierung der InputConnection. Produktions-`TextInsertService` blieb unverändert.

Grenzen: Die Live-Diktate und Browser-Fixtures ergänzen sich, sind aber unterschiedliche Tests. Aus der vorhandenen InputConnection im normalen Browserlauf wird nicht behauptet, dass jeder einzelne normale Fall ausschließlich diesen Weg nutzte. Die Nutzerabnahme sowie die abschließenden Browser-Fixtures sind auf beiden Geräten abgeschlossen; bei der SDK-Fehlerbedingung nahe 30 Sekunden wird weiterhin ein neuer, kürzerer Versuch benötigt. Android 10–12 wurde in dieser Abnahme nicht praktisch geprüft. Es wurden keine persönlichen Diktate oder Gerätekennungen in dieses Protokoll aufgenommen.

# Direkte Browser-Einfügung – 28. September 2026

## Ursache und Korrektur

Der letzte Placeholder-Fix (Commit `782c15c`) verweigerte den Accessibility-Pfad mit
`if (!node.isShowingHintText && !node.text.isNullOrEmpty()) return false`.
Ohne passende InputConnection führten dadurch sowohl echte Texte als auch falsch
als Text gemeldete Placeholder unmittelbar zum bestehenden Clipboard-Fallback.

Produktionsänderungen ausschließlich in `TextInsertService.kt`:

1. Android 13+: passende InputConnection bevorzugen. Bei fremden Editoren deren
   Erreichbarkeit über `getSurroundingText` prüfen und nach `commitText` den
   eingefügten Text vor dem Cursor abfragen. Die Accessibility-Variante von
   `commitText` hat keinen booleschen Rückgabewert (siehe
   [Android-API](https://developer.android.com/reference/android/accessibilityservice/InputMethod.AccessibilityInputConnection)).
   Eigene Editoren teilen den Main-Thread; dort bleibt der asynchrone Commit ohne
   blockierende Rückfrage erhalten.
2. Bei fehlender, nicht antwortender oder nicht bestätigter Connection sowie
   Exceptions den Accessibility-Pfad versuchen: vorhandenen Text erhalten,
   Auswahl ersetzen bzw. am Cursor einfügen, `ACTION_SET_TEXT`, anschließend
   Auswahl setzen und bestehende verzögerte Cursor-Korrektur ausführen.
   Der Text wird vor dem Commit zusammengesetzt, damit ein nicht bestätigter
   Commit beim anschließenden SET_TEXT nicht doppelt angehängt wird.
3. Erst wenn kein direkt nutzbares Ziel bzw. kein erfolgreicher direkter Weg
   vorhanden ist, liefert `insert` false an den unveränderten Clipboard-Fallback.
   Ein Fehler beim nachträglichen Cursor-Setzen macht eine bereits erfolgreiche
   Einfügung nicht nachträglich zum Clipboard-Fall.

Nur `isShowingHintText` wird als explizites Placeholder-Signal verwendet.
Mehrdeutiger `node.text` wird als vorhandener Inhalt behandelt. Daher kann ein
falsch gemeldeter Placeholder im Text landen. Bei ungültiger Cursorposition wird
am Ende angefügt. Keine App-, Domain- oder Placeholder-Text-Sonderfälle.

## Gerät und Ergebnisse

HONOR YLE-W09, Android API 36; Chrome 153.0.8010.52.
Debug-APK per `adb install -r` installiert, ohne Datenlöschung.
Version bleibt 0.4 / versionCode 4. Kein Release erstellt.

Zwölf HTML-Feldtests in echtem Chrome, mit DOM-Prüfung von Inhalt **und** Cursor:

| Fall | InputConnection verfügbar | InputConnection ausdrücklich deaktiviert |
| --- | --- | --- |
| Leeres Input | bestanden | bestanden |
| Textarea mit Placeholder | bestanden | bestanden |
| Vorhandenen Text ergänzen | bestanden | bestanden |
| Cursor mitten im Text | bestanden | bestanden |
| Markierte Auswahl ersetzen | bestanden | bestanden |
| Contenteditable, Cursor mitten im Text | bestanden | bestanden |

Der Test setzt den fertigen Text `Blitzprobe ` über `rememberFocusedField()` und
`insert()` ein. Alle Fälle liefern true und den erwarteten Browser-Inhalt. Damit
wird der Clipboard-Zweig des unveränderten Aufrufers nicht benötigt. Der Test
schreibt nicht in die Zwischenablage, ruft keine Provider auf und schreibt keine
Statistikdaten. Der Prozess bleibt für die verzögerte Cursor-Korrektur offen.

Zusätzlich auf den echten Startseiten von **ChatGPT, Grok und Gemini** jeweils in
einen leeren Composer direkt eingefügt, ausdrücklich ohne InputConnection:
alle drei erfolgreich. Testtexte anschließend entfernt, nichts abgesendet.
Geminis erster Testlauf wurde durch HONORs `iAwareF[CrashClean]` per Force-Stop
beendet; der isolierte Wiederholungslauf war erfolgreich.

Grenzen: Kein vollständiger Mikrofon-/STT-Durchlauf, kein separates eingebettetes
WebView und kein Android-10–12-Gerät getestet. KI-Webapp-Tests decken die leeren
Composer ab; Text-/Cursorvarianten wurden in den Chrome-HTML-Feldern geprüft.
Die InputConnection-Verfügbarkeit allein beweist nicht, dass jeder normale
Testfall ausschließlich diesen Pfad benutzt hat; der erzwungene Accessibility-
Durchlauf weist den reparierten zweiten direkten Weg ausdrücklich nach.

Die zuvor deaktivierte Bedienungshilfe wurde nach den Tests auf ihren
Ausgangszustand zurückgesetzt. Für manuelle Diktate **TapStop Texteingabe wieder
aktivieren** und bei Bedarf die Overlay-Blase starten.

## Build und Wiederholung

`assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: erfolgreich mit JDK 17.
Produktions-APK: `app/build/outputs/apk/debug/app-debug.apk`

SHA-256: `c5f55185908b43e736609190c8cce3077b4e8a80b9a82a35a638e81e29a91453`

Für die zwölf reproduzierbaren Browserfälle beide Debug-APKs installieren,
einen **entbehrlichen neuen Chrome-Tab auf dem Tablet im Vordergrund** öffnen,
`adb forward tcp:9222 localabstract:chrome_devtools_remote` ausführen und dessen
ID aus `http://127.0.0.1:9222/json` übergeben:

```sh
node scripts/browser-insert-tests.mjs <tab-id>
```

Das Skript ersetzt den Inhalt dieses Tabs durch HTML-Testfelder, verbindet die
Bedienungshilfe für die Instrumentierung neu und stellt die ursprüngliche Liste
aktivierter Bedienungshilfen im `finally` wieder her. Der neue `browserInput`
Runner-Zweig überspringt alle Statistik-/Provider-/Clipboard-Tests.
