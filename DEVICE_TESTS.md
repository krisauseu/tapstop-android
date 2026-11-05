# TapStop 0.11 – Accessibility-Overlay am 5. November 2026

Geprüft auf dem per USB verbundenen **HONOR YLE-W09**, Android API 36, Chrome 156. Die Bedienungshilfe `TextInsertService` besitzt jetzt die Fenster für Blase und Modusfächer. Der Mikrofon-Foreground-Service startet weiterhin aus der sichtbaren App und verarbeitet das Diktat. Eine getrennte Bedienungshilfe entfernt beide Fenster. Bei erneuter Verbindung erscheint die Blase wieder, solange der Mikrofon-Dienst läuft. Die Einfügepfade über InputConnection, Accessibility und Clipboard-Fallback wurden beibehalten.

| Prüfung | Ergebnis |
| --- | --- |
| Build und Tests | `testDebugUnitTest lint packageTapStop assembleDebugAndroidTest` mit bestehender Qualcomm-Runtime erfolgreich. 111 JVM-/Robolectric-Tests und 18 Python-Tests (Diagnose und Qualcomm-Vertragstests) bestanden. Lint meldete 0 Fehler und 28 Warnungen. |
| Update | `adb install -r` von 0.9 auf 0.11 erfolgreich. Application-ID `de.kf.blitztext` und Zertifikat stimmen mit 0.9 und 0.10 überein. Direkt nach dem Update waren `settings.xml` und `usage.db` bytegleich. Der vorhandene Provider-Key war in der Geräte-Instrumentierung weiter entschlüsselbar. |
| Fenstertyp | `dumpsys window windows` zeigte für `TapStop-Blase` und `TapStop-Modusfächer` jeweils `ty=ACCESSIBILITY_OVERLAY` und eine sichtbare Oberfläche. Das APK-Manifest enthält kein `SYSTEM_ALERT_WINDOW`. |
| Gesten und Persistenz | Einfaches Antippen startete und stoppte die Mikrofonaufnahme. Ziehen vom rechten zum linken Rand rastete ein. Langes Drücken öffnete den Fächer, alle vier Modi ließen sich auswählen und wurden gespeichert. Position und Modus blieben beim Stoppen und erneuten Starten erhalten. Die ursprünglichen Werte wurden nach dem Test wiederhergestellt. |
| Dienst-Lebenszyklus | Drei Zyklen mit geöffnetem Fächer: Beim Deaktivieren verschwanden beide Fenster, der Mikrofon-Dienst blieb im Vordergrund. Nach Reaktivierung erschien jeweils genau eine Blase. Stopp in der App, Stopp bei getrennter Bedienungshilfe und Prozessneustart hinterließen keine Fenster. Nach Stopp bei getrennter Bedienungshilfe blieb die Blase beim Verbinden aus, bis sie in TapStop erneut gestartet wurde. Keine TapStop-Abstürze im Prüfzeitraum. |
| Mikrofon in Chrome | Ein 6,15-s-Sprachclip wurde über den Lautsprecher abgespielt und vom Tablet-Mikrofon aufgenommen. Das Transkript erschien im zuvor leeren, fokussierten Chrome-Feld. Ein erster Versuch mit einer leeren Audiodatei auf dem Mac bestätigte die Mikrofonaktivierung, lieferte aber kein Transkript. |
| Manuelles Einfügen in Chrome | Bei sichtbarer Blase je dreimal Text und ein 48×32-PNG aus der Zwischenablage per Android-Strg+V eingefügt. Chrome setzte den Text ins Textfeld und lieferte das PNG an den Paste-Handler des Bildeditors, der es anzeigte. In diesen sechs Versuchen trat der gemeldete Dialog nicht auf. |
| Einfüge-Regression | `scripts/device-tests.py` bestand einschließlich nativer Einfügefälle und Clipboard-Fallback. Die Chrome-Suite bestand alle zwölf Fälle, sechs über InputConnection und sechs bei deaktivierter InputConnection über Accessibility. |

Android behandelt Accessibility-Overlays laut [Fenstertyp-Referenz](https://developer.android.com/reference/android/view/WindowManager.LayoutParams) als vertrauenswürdig. Der sporadische Einfügedialog hatte keinen reproduzierbaren Ausgangsfall. Die erfolgreichen Stichproben zeigen, dass beide Paste-Arten unter den getesteten Bedingungen funktionieren. Ob der Dialog im Alltag erneut auftritt, bleibt offen.

**Galaxy S24 Ultra (SM-S928B, Android API 37):** Dieselbe endgültige APK aktualisierte die vorhandene 0.10 per `adb install -r`. Direkt danach waren `settings.xml` und `usage.db` bytegleich. Auf dem S24 wurde das Update geprüft. Die Bedienungshilfe war nach dem Update deaktiviert.

**APK:** `app/build/outputs/apk/tapstop/TapStop-Android-0.11.apk` · 47.906.076 Bytes · SHA-256 `8bbc83454ae37a4d32140523871d779dd14855739803dcadc82a6184ea348b58`. Die endgültige APK enthält zusätzlich den aktualisierten Hinweis in den Android-Bedienungshilfe-Einstellungen. Auf dieser APK wurden Build, JVM-Tests sowie Update und Dienst-Neustart erneut geprüft.

# Geräteabnahme 0.9 — 5. Oktober 2026

**Geräteabnahme abgeschlossen: Local/Roh und Local + Groq/Plus samt Texteingabe sind auf beiden Geräten vom Nutzer abgenommen. Auf beiden läuft derselbe endgültige APK-Stand mit geprüfter SDK-Fehlerbehandlung und bestandenen Browser-Fixtures; beide separaten PoC-Apps sind entfernt.** Die folgenden Werte stammen aus der integrierten TapStop-App, nicht aus den Forschungs-PoCs. Die bekannte SDK-Fehlerbedingung nahe 30 Sekunden und der frühere fehlgeschlagene MagicPad-Plus-Lauf bleiben unten ausdrücklich dokumentiert. Diese Abnahme ist keine Zusage, dass jedes Diktat bis 30 Sekunden erfolgreich transkribiert wird.

## Build, Update und Regression

- Version 0.9 / `versionCode=9`, bestehende Application-ID `de.kf.blitztext` und bisherige Signatur. S24 per `adb install -r` aktualisiert; TapStop weder deinstalliert noch App-Daten gelöscht.
- Vor der absichtlichen Auswahl von Local in der UI waren bestehende Preferences und Statistikdaten gegenüber der Sicherung unverändert. Die anschließenden Diktate ergänzen die bestehende Historie; SQLite bleibt auf Schema Version 1 mit unveränderten Spalten.
- Letzter geprüfter JVM-/Robolectric-Lauf: **59 Tests, 0 Fehler, 0 Fehlschläge, 0 übersprungene Tests**. Darunter Target-/Modellprüfung, Importfehler, Runtime-Lifecycle, Roh ohne Rewrite, die Behandlung des SDK-Markers `SPECTROGRAM FAIL` und der tatsächliche Service-Fall „nach Stop von Roh/Local auf Plus/Groq wechseln, während die lokale Aufnahme noch abgeschlossen wird“.
- Geräte-Instrumentierung erfolgreich: Wortzählung, Mediane, gewichtete WPM, Echtzeitfaktor, Teilmetriken bei Fehlern, Zeiträume, Datenbankpersistenz/-schema/-reset sowie native Einfügevarianten und Clipboard-Fallback. Fixtures liegen in einer separaten Testdatenbank. Ein vorhandener Provider-Key blieb entschlüsselbar; Schlüssel wurden nicht ausgegeben.
- Reale OpenAI- und Groq-Provideraufrufe mit synthetischem Testaudio: jeweils **Roh und Plus erfolgreich**. Dies sind Live-Provider-Regressionen, keine zusätzlichen echten Mikrofonabnahmen.
- Zwölf Chrome-Einfügevarianten bestanden, einschließlich ausdrücklich deaktivierter InputConnection: [Einfügeprotokoll](INSERT_DEVICE_TESTS.md).
- Der zuletzt geprüfte lokale APK-Stand enthält keine `libQnnHtpPrepare.so`. Die Encoder-/Decoder-Ausführung verwendet die finalisierten, gepinnten HTP-Contexts.
- Endgültige auf beiden Geräten installierte Runtime-APK: **85.241.179 Bytes**, SHA-256 `9824e0b7cae8a746d69f8fd97edc143858e29c89c6c66706b1364f9d7f21a8d9`.

## S24 Ultra: lokale Spracherkennung

Read-only erkannt: Samsung `SM-S928B`, `ro.soc.model=SM8650`, QTI, `arm64-v8a`, Android API 36. TapStop wählt `sm8650-v75`, QNN `socModel=57`, HTP V75. Das final validierte VoiceAI-Modell wurde im eigenen TapStop-App-Speicher bereitgestellt und anhand der gepinnten Dateigrößen/SHA-256 geprüft. Kein Modell wird aus dem Speicher der PoC-App ausgeführt.

Synthetische lokale Wiederholungsprüfungen waren erfolgreich; der native Nachweis bestätigt App-Skeleton, CDSP Domain 3 sowie Encoder und Decoder auf HTP V75. Anschließend wurden vier echte Roh-Diktate verarbeitet und in Chrome eingefügt. Der Nutzer bestätigte die korrekte Erkennung und die direkte Einfügung. Das ist eine qualitative Nutzerabnahme, keine unabhängig annotierte Wortfehlerratenmessung.

| Roh-Diktat | Aufnahme | STT | Gesamt ab Stop | Aufnahme/STT | Encoder laut SDK | HTP |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| 1 | 13,200 s | 0,734 s | 0,821 s | 17,98× | 432 ms | V75 bestätigt |
| 2 | 16,480 s | 1,330 s | 1,394 s | 12,39× | 412 + 422 ms | V75 bestätigt |
| 3 | 6,020 s | 0,611 s | 0,674 s | 9,85× | 420 ms | V75 bestätigt |
| 4 | 10,480 s | 0,763 s | 0,818 s | 13,74× | 421 ms | V75 bestätigt |

Für jeden dieser vier erfolgreichen Läufe ist derselbe belegte CDSP-Handle während des jeweiligen Encoderlaufs und sämtlicher Decoderaufrufe bestätigt. Lauf 2 enthält zwei interne SDK-Abschnitte; TapStop übergibt weiterhin eine einzige vollständige WAV und führt kein eigenes Chunking aus. Kein Whisper-CPU-Fallback. Die frühere Modellinitialisierung ist nicht Bestandteil der STT-Zeiten.

Die echte Statistik enthält für diese vier Diktate `provider=qualcomm`, `model=whisper-large-v3-turbo`, `mode=BLITZTEXT`, `target_package=com.android.chrome` und `success=1`. Aufnahmezeit insgesamt **46.180 ms**, STT insgesamt **3.438 ms**, daraus gewichtete **13,43× Echtzeit**; STT-Median **748,5 ms**. `rewrite_ms` ist bei allen vier Roh-Diktaten `NULL`. Diese wenigen, unterschiedlich langen Aufnahmen begründen keine allgemeine Performancegarantie.

Ein fünfter, kurzer Versuch mit **2,420 s** lieferte keine erkannte Sprache und wurde als fehlgeschlagen gespeichert: `success=0`, Roh-/Finalwortzahl sowie STT-/Rewrite-/Gesamtzeit `NULL`. Der native Nachweis enthält dafür keinen Encoder-/Decoderabschnitt. Das zusammenfassende Prüfartefakt meldet deshalb nicht sämtliche fünf Sitzungen als erfolgreich bestätigt. Dieser sprachlose Fehlerlauf ist **kein Nachweis eines HTP-Ausfalls und kein CPU-Fallback**; die vier erfolgreichen Diktate bleiben jeweils positiv bestätigt. Es wurde kein erfolgreicher Text für den fünften Versuch behauptet.

### S24: Plus, Autostopp und Prozess-Lifecycle

Ein anschließendes echtes Diktat mit **Local-STT und Groq-Rewrite im Modus Plus** wurde erfolgreich verarbeitet und direkt in Chrome eingefügt. Der Nutzer bestätigte das Ergebnis als vollständig korrekt. Der Autostopp beendete die Aufnahme an der 30-s-Grenze; tatsächlich geschrieben wurden **29.940 ms PCM-Audio**, nicht exakt 30.000 ms. Damit ist ein automatisches Ende ohne Überschreiten des lokalen Limits praktisch belegt.

| Aufnahme | Local-STT | Groq-Rewrite | Gesamt ab Stop | Aufnahme/STT | Encoder | Decoder | Ergebnis |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 29,940 s | 1,397 s | 1,692 s | 3,193 s | 21,43× | 444 ms | 65 Aufrufe | erfolgreich, V75 bestätigt |

Die strenge native Prüfung bestätigt sämtliche Kriterien: passendes Target, App-lokaler V75-Skeleton, CDSP Domain 3 sowie derselbe belegte FastRPC-Handle während des Encoders und aller 65 Decoderaufrufe. Es gab einen SDK-Abschnitt, kein äußeres Chunking und keinen Whisper-CPU-Fallback. Die Statistik speichert `provider=qualcomm`, `model=whisper-large-v3-turbo`, `mode=PLUS`, `target_package=com.android.chrome`, `success=1` und die getrennten STT-/Rewrite-Zeiten. Die Browserprüfung bestätigte direkte Einfügung von 201 Zeichen beziehungsweise 29 Wörtern und Cursorposition 201; persönliche Inhalte werden nicht übernommen.

Die vier früheren Roh-Diktate liefen innerhalb eines Prozesses mit genau einer nativen Initialisierung. Nach dem späteren APK-Update wurde im neuen Prozess für den Plus-Test ebenfalls genau einmal initialisiert. Die Wiederverwendung innerhalb eines Prozesses ist damit praktisch belegt; ein nativer `deInit → init`-Zyklus im selben Prozess wurde nicht eingeführt.

Nach dem Plus-Lauf: Prozess-PSS **92.562 kB**, RSS **174.456 kB**, Swap-PSS **18.364 kB**, Native Heap **14.336 kB**; Android-Thermalstatus **0** zum Messzeitpunkt. Diese Prozesswerte erlauben keine vollständige Zuordnung des DSP-Speichers und sind keine Messung des gesamten NPU-Speicherbedarfs. Es wurde keine thermische Fehlerbedingung beobachtet; daraus folgen weder eine Langzeit-/Akkuaussage noch eine Leistungszusage.

### S24: endgültige APK und SDK-Fehlerbehandlung

Nach der späteren SDK-Fehlerkorrektur wurde das S24 erneut per Update auf denselben APK-Stand wie das MagicPad gebracht. Preferences und Statistikdatenbank waren vor dem Update und nach sämtlichen abschließenden Regressionen einschließlich Browser-Fixtures byteidentisch; **126 vorhandene Statistikzeilen**, Schema Version 1, Einstellungen und verschlüsselte Keys blieben erhalten. Die beiden eigenen synthetischen Testaufnahmen wurden anschließend entfernt; die bereits deinstallierte PoC-App blieb entfernt.

Die vollständige Modellhashprüfung und einmalige Initialisierung dauerten **6.877 ms**. Die absichtlich fehlerhafte native Fixture mit **29.980 ms** Audio wurde als `SPECTROGRAM_FAIL` abgewiesen: **0 Rewrite-Aufrufe, 0 Teiltext-Übergaben**, Runtime danach weiterhin `READY`. Ein ausdrücklich gestarteter gesunder Folgelauf mit **8.427 ms** Audio gelang in **893 ms STT** im selben Prozess, weiterhin mit genau einer nativen Initialisierung. Der strenge HTP-Nachweis bestätigt dessen V75-Encoder (**427 ms**) und alle **40 Decoderaufrufe** am selben belegten CDSP-Handle. Die erwartete fehlgeschlagene erste Sitzung zählt im zusammenfassenden Prüfartefakt korrekt nicht als erfolgreiche Transkription; sie ist kein positiver Qualitätsbeleg.

Damit sind Fehlerablehnung und anschließende Weiterverwendung ohne `deInit → init`, CPU-Fallback oder automatischen Cloudaufruf auch auf dem endgültigen S24-Stand praktisch bestätigt. Der erste abschließende Browser-Fixture-Fall scheiterte mit „Direct insertion failed“. Die Android-Bedienungshilfe sah dabei ausschließlich `com.android.systemui` ohne fokussierten Eingabeknoten: Der Benachrichtigungsbereich war offen, obwohl Chrome über CDP ein sichtbares und fokussiertes Dokument meldete. Nach dem Schließen dieses Bereichs bestand der vollständige Wiederholungslauf **alle zwölf Fälle**, einschließlich sechs Fällen mit ausdrücklich deaktivierter InputConnection. Die Produktions-Einfügelogik blieb unverändert; der erste Lauf wird nicht als bestanden gezählt.

### S24: Forschungs-App entfernt

Nach erfolgreicher Roh-/Plus-Abnahme wurde ausschließlich die separate S24-PoC-App **`de.feichtinger.whispernpupoc`** deinstalliert; `adb uninstall` meldete Erfolg. Der Paketname wurde zuvor gegen PoC-Buildkonfiguration und Forschungsbericht geprüft. Unmittelbar vor der Entfernung wurden die drei TapStop-Modellhashes und die lokale Modellkopie auf dem Entwicklungsrechner erneut mit den bestätigten Werten abgeglichen; Forschungs-APK, `RESULTS.md` und übrige Referenzartefakte bleiben dort erhalten.

TapStop blieb installiert und lief nach der PoC-Entfernung im selben Prozess weiter. Sein eigener Modellbestand blieb vorhanden. TapStop wurde weder deinstalliert noch wurden seine App-Daten gelöscht. Die S24-PoC-Bereinigung ist damit **abgeschlossen**; die später ebenfalls abgeschlossene MagicPad-Bereinigung ist unten dokumentiert.

## MagicPad: Update, Modelle und Regression

Read-only erkannt: HONOR `YLE-W09`, `ro.soc.model=SM8845P`, QTI, `arm64-v8a`, Android API 36. Update von TapStop **0.8 auf 0.9** ohne Deinstallation oder Datenlöschung. Vor der absichtlichen Local-Auswahl blieben Preferences und Statistikdatenbank auch über sämtliche synthetischen Regressionen hinweg byteidentisch; Einstellungen, Hashes der verschlüsselten Keys, **177 vorhandene Diktate** und Schema Version 1 waren unverändert.

Die automatische Zuordnung wählt `sm8845-v81`, QNN `socModel=97`, HTP V81. Die drei Modelle in TapStops eigenem Speicher stimmen exakt mit den geprüften Größen/SHA-256 des selbst erzeugten SM8845-Pakets überein. Beim Entwicklertransfer musste der von der ADB-Shell angelegte unterste Modellordner auf Zugriffsmodus `755` gesetzt werden, damit die App ihn lesen konnte. Dies betraf ausschließlich den eigenen App-Modellordner; kein Root und keine Änderung an `/vendor` oder anderen Systempfaden.

Zwei synthetische lokale Läufe mit je **8,427 s** Audio benötigten **672 / 671 ms STT**. Die einmalige Vorbereitung einschließlich vollständiger Hashprüfung und Initialisierung dauerte **4.353 ms** und ist nicht in den STT-Werten enthalten. Der strenge native Nachweis bestätigt für beide Läufe den App-lokalen V81-Skeleton, CDSP Domain 3 sowie Encoder und sämtliche Decoderaufrufe am jeweils belegten Handle. Encoderzeiten: **339 / 338 ms**; jeweils 40 Decoderaufrufe. Kein Whisper-CPU-Fallback.

Vier Live-Regressionen mit synthetischem Audio bestätigten beide Cloudprovider:

| Provider / Modus | STT | Rewrite | Gesamt ab Stop | Ergebnis |
| --- | ---: | ---: | ---: | --- |
| OpenAI / Roh | 2.305 ms | – | 2.311 ms | erfolgreich |
| OpenAI / Plus | 1.352 ms | 1.842 ms | 3.198 ms | erfolgreich |
| Groq / Roh | 681 ms | – | 686 ms | erfolgreich |
| Groq / Plus | 873 ms | 1.023 ms | 1.901 ms | erfolgreich |

Dies sind reale Provideranfragen und keine Performancevergleichsstudie. Der Replay bestätigte abgeschlossene Worker, entfernte Testaufnahmen und keine offenen eigenen Verbindungen. Die bestehenden nativen Statistik-/Einfügetests bestanden ebenfalls. Bei den Browser-Fixtures scheiterte zunächst die erste Einfügung; der vollständige unveränderte Wiederholungslauf bestand alle zwölf Fälle. Ein möglicher Rebind-/Instanz-Race im Testrunner wurde eingegrenzt, ist als Ursache dieses ersten Fehlers jedoch nicht bewiesen. Dieser erfolgreiche Wiederholungslauf lag vor der gezielten Harness-Absicherung. Anschließend bestand auch die neue Test-APK mit dieser Absicherung alle zwölf Fälle. Die Produktions-Einfügelogik wurde dafür nicht verändert; Details in [INSERT_DEVICE_TESTS.md](INSERT_DEVICE_TESTS.md).

### MagicPad: erstes echtes Roh-Diktat angenommen

Ein echtes Diktat mit **20,520 s** Aufnahme wurde lokal in **1,095 s** transkribiert; Gesamtzeit ab Stop **1,132 s**, entsprechend **18,74× Echtzeit**. Die direkte Einfügung in Chrome ist technisch geprüft: **104 Zeichen, 22 Wörter, Cursorposition 104**. Der neue Statistikdatensatz ergänzt die 177 erhaltenen Einträge mit `provider=qualcomm`, `model=whisper-large-v3-turbo`, `mode=BLITZTEXT`, `target_package=com.android.chrome`, `rewrite_ms=NULL` und `success=1`.

VoiceAI verarbeitete die einzelne WAV in zwei internen SDK-Abschnitten: Encoder **343 + 340 ms**, Decoder **20 + 19 Aufrufe**. Die native Prüfung bestätigt sämtliche Kriterien einschließlich derselben belegten V81-/CDSP-Handle-Kette für beide Encoder und alle Decoderaufrufe. TapStop hat keine eigenen Chunks erzeugt. Der Nutzer nahm das Ergebnis mit einer Eigennamenabweichung an: „Hunora“ statt „Honor“. Dies ist eine qualitative Abnahme dieses Roh-Diktats, keine fehlerfreie Transkription oder unabhängig annotierte Wortfehlerratenmessung.

### MagicPad: erster Plus-Lauf nicht abgenommen, SDK-Fehler abgefangen

Der erste echte Local-/Groq-Plus-Versuch endete per Autostopp bei **29.980 ms** Audio. Die gemessenen Zeiten waren **981 ms STT**, **1.337 ms Rewrite** und **2.366 ms gesamt**. Encoder und Decoder auf HTP V81 sind technisch nachgewiesen. Der Nutzer meldete jedoch den angehängten Text `SPECTROGRAM FAIL`; dieser Lauf ist deshalb **kein erfolgreich abgenommenes Plus-Diktat**. Die vor der Korrektur fälschlich als erfolgreich gespeicherte historische Statistikzeile 179 bleibt erhalten und gilt nicht als positiver Qualitätsbeleg.

Der native Ablauf lieferte nach einem leeren verbleibenden Audiopuffer einen finalen Callback mit Fehlercode `0` und dem vollständigen Text `SPECTROGRAM FAIL`. TapStop hatte diesen SDK-Marker als Transkript übernommen und anschließend den Rewrite gestartet. Die korrigierte Behandlung erkennt genau diesen vollständigen Callback als STT-Fehler (`IOException`), verwirft bereits empfangenen Teiltext und verhindert Rewrite und Ergebnisübergabe. Sie entfernt keine solchen Wörter aus normalem Diktattext. Die darunterliegende SDK-Eigenschaft bleibt bestehen; weder eigenes Chunking noch eine VAD-Umgehung wurde eingeführt.

Eine native Fixture mit **29.980 ms** reproduzierte den früheren falschen Erfolg zweimal. Mit der korrigierten App bestätigte die Geräte-Instrumentierung denselben Fall als erwarteten Fehler: **0 Rewrite-Aufrufe, 0 Teiltext-Übergaben**, Runtime danach weiterhin `READY`. Ein anschließend ausdrücklich gestarteter gesunder Lauf mit **8.427 ms** Audio war mit **664 ms STT** erfolgreich. Beide Durchläufe verwendeten denselben Prozess mit einer nativen Initialisierung; kein automatischer Wiederholungsversuch und kein CPU- oder Cloud-Fallback.

Bekannte Grenze: Bei Aufnahmen nahe 30 Sekunden kann das VoiceAI-SDK diesen Fehler liefern. TapStop zeigt dann einen STT-Fehler; ein neues, kürzeres Diktat ist erforderlich. Das automatische Aufnahmeende ist mit 29.980 ms belegt, ein erfolgreicher vollständiger MagicPad-Plus-Durchlauf an der Grenze hingegen nicht. Die folgende erfolgreiche kürzere Wiederholung hebt diese Einschränkung nicht auf.

### MagicPad: korrigierter Plus-Durchlauf angenommen

Mit der korrigierten App wurde ein neues echtes Diktat mit **20.060 ms** Aufnahme verarbeitet. Der Nutzer bestätigte das Plus-Ergebnis als „Perfekt und ohne Fehler“. Local-STT benötigte **737 ms**, Groq-Rewrite **1.049 ms**, die Gesamtverarbeitung ab Stop **1.829 ms**; für diesen einzelnen Lauf ergibt sich **27,22× Echtzeit**. Die direkte Chrome-Einfügung ist mit **126 Zeichen, 18 Wörtern und Cursorposition 126** geprüft.

Der strenge native Nachweis bestätigt alle Kriterien einschließlich App-lokalem V81-Skeleton, CDSP Domain 3, Encoder **338 ms** sowie sämtlichen **37 Decoderaufrufen** am selben belegten FastRPC-Handle. Ein SDK-Abschnitt, keine eigenen Chunks und kein Whisper-CPU-Fallback. In diesem App-Prozess wurde genau einmal nativ initialisiert.

Die neue Statistikzeile 180 enthält `provider=qualcomm`, `model=whisper-large-v3-turbo`, `mode=PLUS`, `target_package=com.android.chrome`, `success=1` und getrennte STT-/Rewrite-Zeiten. Die UI zeigt Qualcomm NPU und Whisper Large V3 Turbo als eigene Provider-/Modellgruppe. Die dort angezeigten **25,1×** und der STT-Median von **0,98 s** schließen allerdings die erhaltene falsche Erfolgsmarkierung aus Zeile 179 ein und werden deshalb **nicht als gültiger Leistungsbenchmark** verwendet. Die historische Nutzerdatenbank wurde nicht nachträglich bereinigt.

Nach dem erfolgreichen Plus-Lauf: Prozess-PSS **111.149 kB**, RSS **266.608 kB**, Swap-PSS **5 kB**, Native Heap **22.148 kB**; Android-Thermalstatus **0** zum Messzeitpunkt. Die Werte beschreiben den App-Prozess und erfassen nicht vollständig den DSP-Speicher. Aus diesem einzelnen Messpunkt folgt keine Langzeit-, Akku- oder thermische Leistungszusage.

### MagicPad: Forschungs-App entfernt

Nach der erfolgreichen Roh-/Plus-Abnahme wurde ausschließlich **`de.feichtinger.whispermagicpadpoc`** deinstalliert; `adb uninstall` meldete Erfolg. Die Forschungs-APK und `RESULTS.md` auf dem Entwicklungsrechner sowie die drei Hashes der gesicherten Modellkopie und des TapStop-eigenen Modellbestands waren zuvor erneut geprüft. TapStop blieb installiert, lief danach im selben Prozess weiter und behielt seine eigenen Modelle. Damit sind **beide separaten PoC-Apps entfernt**. Die Forschungsverzeichnisse und Artefakte auf dem Entwicklungsrechner bleiben erhalten.

## Abschluss und Grenzen

Die Abnahme beider Geräte ist abgeschlossen: echte Roh-/Plus-Diktate samt Texteingabe und Statistik, passende app-eigene Modelle, nachgewiesene HTP-/FastRPC-Ausführung, bestehende Cloudprovider, Datenbestandserhalt, endgültiger APK-Stand und PoC-Bereinigung. Die SDK-Fehlerablehnung mit anschließender expliziter erfolgreicher Sitzung sowie die Browser-Fixtures sind auf beiden Geräten geprüft.

Die SDK-Fehlerbedingung nahe 30 Sekunden bleibt bekannt. Der erste MagicPad-Plus-Versuch zählt weiterhin nicht als bestanden; seine historische falsche Erfolgsmarkierung wird nicht rückwirkend verändert. Andere Geräte mit gleichem SoC sind damit nicht automatisch validiert. Android 10–12, längerfristige Wärme-/Akkueffekte und allgemeine Erkennungsqualität wurden in dieser Abnahme nicht praktisch vermessen. Commit und Veröffentlichung sind getrennte nachfolgende Schritte.

Die ausgewerteten lokalen Belege umfassen strukturierte HTP-Prüfergebnisse, Geräte-Instrumentierungsberichte und eine Metadatenkopie der Statistik. Persönliche Diktate, Audio, Seriennummern und unbearbeitete Logcat-Dumps werden nicht ins Repository übernommen.

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

## Gemini-Pilot auf dem HONOR MagicPad · 5. November 2026

Debug-Update per `adb install -r` auf dem YLE-W09 mit bestehender Application-ID und Signatur. Vor der manuellen Gemini-Auswahl waren die Einstellungen und die Statistikdatenbank bytegleich. Die vorhandenen API-Keys blieben entschlüsselbar. Wörterbuch und Statistik blieben erhalten, die Datenbank blieb auf Schema 1. Anschließend wurde Gemini mit einem separat in der App gespeicherten Key getestet.

### Dieselbe Originalaufnahme auf dem Gerät

Die Originaldatei mit 131,030 s wurde anhand ihrer SHA-256-Prüfsumme verifiziert. Je drei Requests liefen aus TapStops produktivem STT-Pfad auf dem HONOR im selben WLAN, mit wechselnder Reihenfolge der Anbieter. Die STT-Zeiten umfassen die Anfrage bis zum geparsten Transkript.

| Modell | Zielsatz korrekt | STT je Lauf (ms) | Median (ms) |
| --- | ---: | --- | ---: |
| Gemini 3.5 Transcribe (`verbatim`, `de-DE`) | 3/3 | 5.582, 5.832, 5.214 | 5.582 |
| OpenAI Whisper-1 | 3/3 | 9.201, 8.186, 7.058 | 8.186 |

Für diesen Clip lag der Gemini-Median rund 32 % unter der gleichzeitig erhobenen OpenAI-Basis. Die frühere Gemini-Evaluation mit 5.047 ms Median lief auf dem Mac. Ihr Vergleichswert von 9.700 ms stammte aus älteren OpenAI-Läufen auf dem HONOR.

### Mikrofon bis zur direkten Texteinfügung

Deutsche Testdiktate wurden über Lautsprecher abgespielt und mit dem HONOR-Mikrofon aufgenommen. Die vier gewerteten Läufe fügten den Text ohne manuelles Einfügen in ein zuvor leer geprüftes Chrome-Feld ein. Die Einfügediagnose bestätigte jeweils `DIRECT_INPUT_CONNECTION_SUCCESS`.

| Modus | Rewrite | Aufnahme (ms) | STT (ms) | Rewrite (ms) | Gesamt ab Stopp (ms) |
| --- | --- | ---: | ---: | ---: | ---: |
| Roh | – | 5.431 | 2.244 | – | 2.315 |
| Plus | Groq | 20.215 | 2.575 | 962 | 3.611 |
| Chat | Groq | 8.515 | 1.913 | 818 | 2.809 |
| Formal | OpenAI | 10.907 | 2.238 | 3.069 | 5.364 |

Die ersten drei Versuche landeten in der Zwischenablage (`SERVICE_UNAVAILABLE`). Android führte die TapStop-Bedienungshilfe nach der Instrumentierung als aktiviert, aber ungebunden und abgestürzt. Die erneute Verbindung mit derselben Einstellung stellte die direkte Einfügung wieder her. Gewertet wurden die anschließend eindeutig zugeordneten Läufe; die Produktions-Einfügelogik blieb unverändert.

108 JVM-/Robolectric-Tests und 14 Python-Tests bestanden. Android-Lint meldete 0 Fehler und 30 Warnungen. Die Geräte-Instrumentierung bestätigte Statistik, Schlüsselzugriff, native Eingabefelder und Clipboard-Fallback. Alle zwölf Chrome-Einfügefälle bestanden. Groq Whisper Large V3 und Turbo fügten je ein Roh-Diktat direkt ein (1.836 und 1.170 ms STT). Die lokale Qualcomm-Runtime transkribierte denselben 8,450-s-Testclip zweimal im selben Prozess (900 und 925 ms STT). Der Gemini-Files-API-Pfad ist mit simulierten Antworten für Upload, Erfolg, Fehler und Abbruch geprüft. Ein Live-Upload oberhalb des Inline-Limits steht aus. Das persönliche Wörterbuch bleibt für Gemini-STT bis zu einer eigenen Vokabularregression deaktiviert.

## Release 0.10 auf dem Galaxy S24 Ultra · 5. November 2026

- Die finale APK aktualisierte die installierte Version 0.9 per `adb install -r` auf 0.10. Package-ID `de.kf.blitztext` und Debug-Signatur stimmen mit der veröffentlichten 0.9-APK und der zuvor installierten APK überein.
- `settings.xml` und `usage.db` waren unmittelbar nach dem Update und nach Instrumentierung und App-Start bytegleich. Beide vorhandenen Cloud-Keys blieben entschlüsselbar und das Statistik-Schema blieb auf Version 1.
- Die Instrumentierung bestätigte Statistik, direkte Texteingabe in native Felder und Clipboard-Fallback. Die für den Test aktivierte Bedienungshilfe wurde anschließend auf ihren vorherigen Zustand zurückgesetzt.
- Der finale Build mit gepinnter Qualcomm-Runtime, die separate Test-APK, 109 JVM-/Robolectric-Tests und 23 Python-Tests waren erfolgreich. Android-Lint meldete 0 Fehler und 30 Warnungen. Die Opt-out-Tests prüfen Audio, Text und die Einfügemetadaten.
