# Abnahme 0.4 — 27. September 2026

HONOR YLE-W09, Android API 36: Update von versionCode 3 / 0.3 auf versionCode 4 / 0.4 per `adb install -r`, ohne Deinstallation oder Datenlöschung.

- `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug`: erfolgreich mit JDK 17.
- Statistik-Instrumentierung: erfolgreich (`adb shell am instrument -w -e statisticsOnly true de.kf.blitztext.test/de.kf.blitztext.StatsInstrumentation`). Prüft insbesondere 30× Echtzeit, gewichtete Aggregation (40.000 / 4.000 = 10 statt Mittelwert der Faktoren), fehlende/Null-/Nullzeitwerte, Fehler, Provider-/Modellgruppen und Zeitraumfilter. Testdaten ausschließlich in separater Datenbank.
- Dashboard auf dem Tablet: Heute/Woche/Monat/Gesamt geprüft; 3 Diktate, 26 Wörter, 34 s Sprechzeit unverändert. Provider- und Modellanzeige visuell geprüft: STT-Zeit 0,78 s · 13,8× Echtzeit.
- Bestehende Werte: 34.203 ms Aufnahme / 2.474 ms STT = 13,8249797898× Echtzeit. Absolute STT-Zeit bleibt der Median (782 ms).
- Vollständige `usage.db` vor dem Update und nach Gerätetests byteidentisch. SHA-256: `6504dc334313bc17785095576d1429470cc3c6308c126a1778701cb7d21ae828`. Alle drei vorhandenen v0.3-Datensätze erhalten; Schema weiterhin Version 1, keine Migration.
- Vorhandener Provider-Key im Test weiterhin entschlüsselbar (nicht ausgegeben).
- Keine Produktionsänderungen außerhalb Statistikdarstellung/-berechnung und Versionsnummer. Aufnahme-, STT-, Rewrite-, Einfüge- und Providerlogik unverändert. Die derzeit deaktivierte Bedienungshilfe blieb unverändert; Einfüge-/Mikrofontests wurden für diese reine Statistikänderung nicht erneut ausgeführt.

# Abnahme 0.3 — 27. September 2026

Gerät: angeschlossenes HONOR YLE-W09, Android API 36. Update mit `adb install -r`, gleiche Application-ID und vorhandener Debug-Signatur. Kein Uninstall/Clear-Data.

| Prüfung | Ergebnis |
| --- | --- |
| Einstellungen nach Update | Preferences-Datei vor/nach erstem Update identischer SHA-256; vorhandener API-Key im Instrumentierungstest erfolgreich entschlüsselt, ohne Ausgabe des Schlüssels. |
| Neue Startseite | Dashboard standardmäßig, initial 0 Wörter / 0 Diktate / 0 s / 0 WPM. |
| Echter STT-/Mikrofontest | Nutzer bestätigt problemlose Einfügung. Drei reale Groq-/Whisper-Large-V3-Diktate: einmal Roh, zweimal Plus. |
| Metriken | 26 Rohwörter, 26 finale Wörter, 34.203 ms Aufnahmezeit, gewichtete 45,61 WPM (Anzeige 46). STT-Median 782 ms; Gesamt-Median 1.053 ms; Rewrite-Median 457,5 ms. Rewrite beim normalen Diktat NULL. |
| Ziel-Apps | Zwei Diktate in Chrome (15 Wörter), eines in Gmail (11 Wörter). Packages korrekt gespeichert. Namensauflösung über PackageManager und Launcher-Sichtbarkeit, keine zusätzliche Berechtigung. |
| Zeiträume | Heute, Woche, Monat, Gesamt per UI durchgeschaltet; passende Aktivitätsansichten und unveränderte aktuelle Summen. Separate SQLite-Testfixtures prüfen auch frühere Tage, Wochen-/Monatsgrenzen und zukünftige Zeitstempel. |
| Einfügeweg | Automatisiert auf dem Gerät: leere native Felder mit/ohne Placeholder, Anhängen, Cursor mitten im Text, Clipboard-Fallback. Reale Einfügungen zusätzlich vom Nutzer in Chrome/Gmail bestätigt. |
| Reset | Bestätigungsdialog, Abbrechen ohne Löschen, Bestätigen mit 0-Anzeige und leerer persistierter Tabelle erfolgreich. Die drei echten Testdatensätze anschließend aus der reinen Metadaten-Sicherung wiederhergestellt. |
| Persistenz | Echte Datensätze nach Prozessneustart und erneutem APK-Update vorhanden. Separater Test prüft Schließen/Wiederöffnen der SQLite-Datenbank. |
| Datenschutz | Datenbankschema enthält ausschließlich Metadaten; keine Aufnahme-Dateien nach Verarbeitung im App-Cache. Keine Texte/Keys im Testbericht. Backups und Geräteübertragung explizit ausgeschlossen. |
| Build / Lint | `assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` erfolgreich. Bestehende Kompatibilitäts-/Dependency-Hinweise bleiben Warnungen. |

Die separate Test-APK und `scripts/device-tests.py` prüfen außerdem Unicode-Wortzählung, leere Texte, ungerade/gerade Mediane, Fehler mit NULL-/Teilmetriken, gewichtete WPM, Providergruppen und das exakte Metadatenschema. Testfixtures werden in einer separaten Datenbank angelegt und gelöscht.

Der Einfüge-Fix vom vorherigen Arbeitsstand ist unverändert erhalten. In `TextInsertService` kamen nur ein lesender Package-Zugriff und eine API-33-Annotation des bereits vorhandenen Framework-Callbacks hinzu; keine Änderung an Fokus-, Cursor-, InputConnection- oder Fallback-Logik. Die Accessibility-Konfiguration entspricht exakt dem Stand vor Beginn dieses Auftrags.

Grenzen: Kein älteres Android-Gerät/Emulator angeschlossen; Android 10–12 daher nicht erneut praktisch getestet. OpenAI wird durch Aggregationsfixtures abgedeckt, aber in dieser Abnahme nicht mit einem echten Netzwerkdiktat aufgerufen. Fehler-/Timeout-Daten werden mit Fixtures geprüft; die echten Testdiktate waren erfolgreich.
