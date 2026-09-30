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
