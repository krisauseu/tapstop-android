# TapStop 0.12 – Build- und Geräteprotokoll

Prüfdatum 8. November 2026. Grundlage ist der veröffentlichte Tag `v0.11` auf `00f40d54d78d48b398c5662eeed42be3d108c26c`. Dieses Protokoll beschreibt den lokal gebauten Kandidaten.

## Artefakt

| Prüfung | Ergebnis |
| --- | --- |
| APK | `TapStop-Android-0.12.apk`, 38.859.193 Bytes |
| App-ID und Namespace | `de.kf.tapstop` |
| Version | `0.12`, `versionCode=12`, minSdk 29, targetSdk 35 |
| SHA-256 | `e97d18568e0f618f510c448ce09af5d8d23124770cf9893283d5a2d2cee542ea` |
| Zertifikat SHA-256 | `858e783c20cd77e9eb5f5c8bbde096aa152752dc5c92d440aba98c677b3b077e` |
| Signatur | Dedizierter RSA-4096-Schlüssel, APK Signature Scheme v2, `apksigner` und `zipalign` bestanden |
| Manifest | Launcher, Mikrofon-Foreground-Service, geschützter Accessibility-Service und Service-XML geprüft. Backup deaktiviert. APK ohne Debug-Freigabe. |
| Qualcomm | VoiceAI 2.7.1.0 und QAIRT 2.50.0.260828. V75 und V81 mit gepinnten Hashes, SDK-Klassen im DEX, sechs unveränderte SDK-Hinweise. Whisper-Modellpakete werden separat importiert. |

APK und Prüfsummendatei liegen lokal unter `app/build/outputs/apk/tapstop/`.

## Build und Regressionen

- Der vollständige signierte Build und die separat signierte Instrumentierungs-APK wurden mit JDK 17 und dem Gradle-Wrapper gebaut. Der freie Release-Build war ohne SDK-Bundle und Signierdaten erfolgreich. Die unsigned APK ist 20.602.360 Bytes groß. Das Archiv enthält weder Qualcomm-Objekte noch SDK-Klassen oder Qualcomm-Hinweise. Die Local-Auswahl und der Modellimport sind in diesem Build ausgeblendet.
- Je Variante bestanden 111 JVM-/Robolectric-Tests. 18 Python-Diagnose- und Vertragstests plus fünf Tests des HTP-Nachweiswerkzeugs bestanden. Android-Lint meldete 0 Fehler und 28 Warnungen. Die v0.11-Abnahme meldete 111 JVM-/Robolectric-Tests, 18 Python-Tests und ebenfalls 0 Lint-Fehler und 28 Warnungen. [v0.11-Geräteprotokoll](../DEVICE_TESTS.md)
- Die Kotlin-Migration umfasst 45 Dateien. Die Änderungen über den Paketwechsel hinaus betreffen die Sichtbarkeit der Local-Option im freien Build, die Freigabe vorhandener Opt-in-Diagnosen im signierten Build und einen Instrumentierungsparameter für Installationen ohne Cloud-Keys. STT- und Rewrite-Prompts, Gemini-Anbindung, Qualcomm-Inferenz, Einfügealgorithmus und Statistikberechnung sind unverändert.
- Englische Haupt-README und deutsche Übersetzung haben 16 parallele Abschnitte. Relative Links und Abschnittsanker wurden geprüft. Es lagen keine öffentlichen Screenshots im Quellbaum vor. Die vorhandenen privaten Diagnosebilder bleiben außerhalb der Dokumentation.

## Installation und Daten

| Gerät | Vorbestand und Installation | Instrumentierung auf der signierten APK |
| --- | --- | --- |
| Samsung Galaxy S24 Ultra, `SM-S928B`, Android 17 | `de.kf.blitztext` 0.11 und `de.kf.tapstop` 0.12 parallel. Start und Mikrofonfreigabe geprüft. SM8650/V75-Modell über den Dateiauswahldialog importiert. | Statistik, vier native Einfügefälle und Clipboard-Fallback bestanden. Zwei lokale Transkriptionen mit synthetischer 8,427-s-Fixture in derselben SDK-Session bestanden. |
| HONOR MagicPad 4, `YLE-W09`, Android 16 | `de.kf.blitztext` 0.11 und `de.kf.tapstop` 0.12 parallel. Start und Mikrofonfreigabe geprüft. SM8845P/V81-Modell über den Dateiauswahldialog importiert. | Statistik, vier native Einfügefälle und Clipboard-Fallback bestanden. Zwei lokale Transkriptionen mit synthetischer 8,427-s-Fixture in derselben SDK-Session bestanden. |

Auf beiden Geräten stimmte der SHA-256 der installierten APK mit dem Release-Kandidaten überein. Der neue Accessibility-Service hat auf beiden Geräten ein sichtbares Overlay-Fenster angelegt. Der Mikrofon-Dienst lief im Vordergrund. Je zwölf Chrome-Tests bestätigten Text und Cursorposition, sechs mit InputConnection und sechs mit erzwungenem direktem Accessibility-Fallback. Die Cloud-Anbieter OpenAI, Groq und Gemini waren in der Anbieterauswahl sichtbar.

Die Geräte wurden in ADB anhand von Seriennummer, Modell und SoC eindeutig zugeordnet. Beide Altinstallationen enthielten Einstellungen, Provider- und Groq-Modellwahl, drei gespeicherte verschlüsselte Schlüssel, Wörterbuch, Modus, Blasenposition, die private Statistikdatenbank und ein importiertes Modell. Beim HONOR war zusätzlich eine separate Rewrite-Auswahl gespeichert. Geprüft wurde ausschließlich das Vorhandensein. Schlüssel, Diktate und Wörterbucheinträge wurden weder übertragen noch ausgegeben. Alte App-Daten blieben erhalten. Nach dem Import wurden die temporären Quellordner auf den Geräten entfernt. Die importierten Modelle stehen in v0.12 bereit.

Die WAV-Fixture musste für den Test vom WAV-Container mit zusätzlichem INFO-Block in den von TapStop erzeugten 44-Byte-Header umgepackt werden. Die PCM-Nutzdaten blieben identisch. Ein erneuter nativer Handle-Nachweis für Encoder und Decoder fehlt auf der signierten APK. Die historische Hardware-Abnahme bleibt im Geräteprotokoll.

Bei der anschließenden manuellen Prüfung hat der Nutzer auf beiden Geräten die API-Keys neu eingetragen und Spracheingabe und Textausgabe erfolgreich getestet. Die dabei verwendeten Provider und Modi wurden nicht festgehalten.

Der Nutzer hat den Release-Keystore dauerhaft abgelegt, die Passwörter im Passwortmanager hinterlegt und verschlüsselte Sicherungen erstellt. Der Release-Keystore wurde anschließend aus einer verschlüsselten Sicherung wiederhergestellt. Der Zertifikatsfingerprint des wiederhergestellten Keystores wurde erfolgreich mit dem oben angegebenen Fingerprint abgeglichen.

## Vor Veröffentlichung

1. Persönliche Einstellungen und Wörterbuch bei Bedarf manuell übernehmen. Alte Statistiken bleiben im bisherigen Paket.
2. Für die getrennte Abnahme von Local + Roh und Cloud-Rewrite auf beiden Geräten die verwendeten Provider und Modi festhalten. Die dokumentierte VoiceAI-Grenze nahe 30 Sekunden gilt weiterhin.
3. Die Lizenzfreigabe des Maintainers für etwaige außerhalb des Git-Verlaufs entstandene Beiträge und die Bedingungen für die konkrete Qualcomm-Weitergabe prüfen. Der kanonische Apache-2.0-Text liegt bei, die Blitztext-MIT-Attribution bleibt separat. Das Modellinventar dokumentiert die jeweiligen Bedingungen. [Lizenzinventar](QUALCOMM_LICENSING.md)
4. Den Kandidaten aus dem finalen Commit bauen und erneut prüfen, dann `v0.12` taggen und die geprüfte APK mit SHA-256 als GitHub Release bereitstellen. Der Quellbuild-Workflow ist vorbereitet und wurde lokal nachvollzogen. GitHub Actions und eine offizielle F-Droid-Einreichung stehen aus. Die Abhängigkeit des freien Builds von externen STT-Diensten ist als mögliches F-Droid-Anti-Feature dokumentiert. [Release-Ablauf](../RELEASING.md)
