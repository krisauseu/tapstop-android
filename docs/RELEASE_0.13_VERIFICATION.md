# TapStop 0.13 – Build- und Geräteprotokoll

Geprüft am 8. November 2026. Basis ist der separat gesicherte lokale 0.12-Kandidat `89a9a9c`. Der veröffentlichte GitHub-Tag `v0.11` zeigt auf `00f40d54d78d48b398c5662eeed42be3d108c26c`. Die Remote-Abfrage ergab keine Tags `v0.12` oder `v0.13`.

## APKs

| Prüfung | Ergebnis |
| --- | --- |
| App-ID, Kotlin-Namespace | `de.kf.tapstop` |
| Version und SDK | `0.13`, Versionscode 13, minSdk 29, targetSdk 35, compileSdk 36 |
| Signierte APK | `TapStop-Android-0.13.apk`, 38.511.637 Bytes, SHA-256 `7b7e6dde376fa90595764a59227deaa304092432a2930f611489db7fda52c566` |
| Freie APK | `app-release-unsigned.apk`, 20.254.712 Bytes, SHA-256 `0d791a54603671dade88884555a2bf8423ed90b0eafe9b4c1d0842cbb37eaab6` |
| Signatur und Ausrichtung | `apksigner` und `zipalign -c -P 16 4` bestanden. Zertifikat SHA-256 `858e783c20cd77e9eb5f5c8bbde096aa152752dc5c92d440aba98c677b3b077e`, wie bei 0.12. |
| Archivprüfung | Beide APKs enthalten Englisch und Deutsch sowie die Projekt-Lizenzhinweise. Die vollständige APK enthält die gepinnten Qualcomm-Bibliotheken, V75/V81-Skeletons und sechs SDK-Hinweise mit passenden SHA-256-Hashes. Die freie APK enthält keine dieser Dateien und keine Qualcomm-SDK-Klasse. Beide APKs sind frei von Modellgewichten, Aufnahmen und Signiermaterial. |
| Berechtigungen | Per APK-Vergleich identisch zu 0.12. |

Builds mit JDK 17, Gradle 8.13, AGP 8.11.1 und Android SDK 36. Die vollständige APK und ihre Prüfsummendatei liegen lokal in `app/build/outputs/apk/tapstop/`. Für die Veröffentlichung wird aus dem jeweiligen Release-Commit gebaut. [Release-Ablauf](../RELEASING.md)

## Automatisiert auf dem Host

- Vollständiger, signierter Build und freie Variante ohne Qualcomm-SDK gebaut. Die jeweils zugehörigen Instrumentierungs-APKs wurden kompiliert.
- Je Variante 114 JVM-/Robolectric-Tests bestanden, darunter drei neue Lokalisierungstests für regionale Varianten und Fallback, dynamische Statusmeldungen und Pluralformen sowie Activity-Neuerstellung mit vorhandenen Einstellungen und einer Statistik-Fixture im Schema 1. Die Persistenztests prüfen die vier historischen Modus-IDs, Provider und Rewrite-Provider, das Groq-Modell, das Wörterbuch, die Blasenposition und die Statistikzeile.
- Android-Lint für Debug und Release in beiden Varianten mit je 0 Fehlern und 30 Warnungen. Gegenüber dem 0.12-Protokoll betreffen zwei zusätzliche Hinweise das erst ab API 33 verwendete Manifest-Attribut `localeConfig` und den lokal begrenzten Ressourcen-Kontext für englische Pluralregeln im Fallback. Die geprüften APKs enthalten beide Sprachen.
- 23 Python-Tests bestanden. Davon 14 Diagnosetests, 4 Qualcomm-Vertragstests und 5 Tests des HTP-Nachweiswerkzeugs.
- Ressourcenabdeckung umfasst 205 deutsch lokalisierte Strings und 3 Pluralgruppen mit je zwei Formen. Die englische Basis enthält zusätzlich 21 gemeinsam verwendete Einträge für Namen, Symbole und sprachneutrale Formate. Die Ressourcen decken Dashboard, Einstellungen, Wörterbuch, Modellimport, Overlay, Benachrichtigung, Accessibility, Berechtigungen und Rückmeldungen ab. App-eigene Fehlermeldungen werden lokalisiert. Unstrukturierte Fremdfehler werden als lokalisierter Hinweis angezeigt, und bekannte HTTP-Fehler behalten konkrete Hinweise zu API-Key, Anfragelimit oder Statuscode.

## Auf Geräten ausgeführt

Signiertes In-place-Update von 0.12 auf 0.13 auf dem Samsung Galaxy S24 Ultra (SM-S928B, Android 17, V75) und dem HONOR MagicPad 4 (YLE-W09, Android 16, V81). Die SHA-256-Prüfsumme der installierten APK stimmte auf beiden Geräten mit dem lokalen Artefakt überein. Vorher und nachher pro Gerät per schreibgeschützter Instrumentierung geprüft: ausgewählter STT-Anbieter Gemini, zwei entschlüsselbare OpenAI-/Groq-Schlüssel, sieben Wörterbucheinträge und eine vorhandene Statistikzeile im Schema 1. Die zusätzliche Statistik-Instrumentierung konnte auch den ausgewählten Gemini-Schlüssel nach dem Update entschlüsseln. Das Protokoll enthält keine Schlüssel, Wörterbucheinträge oder Diktatinhalte.

Auf beiden Geräten bestanden:

- Android-`LocaleConfig` mit Deutsch und Englisch. Elf konfigurierte Sprachlisten prüfen Deutsch für Deutschland, Österreich und die Schweiz, englische Varianten, Fallback für Französisch und Japanisch, bevorzugte Sprachreihenfolge und die Singular-/Pluralformen einschließlich Null. Die Gerätesystemsprache wurde dafür nicht umgestellt.
- Statistik-Fixtures, vier native Einfügefälle und der Clipboard-Fallback.
- Die Android-Sprachseite zeigte Deutsch und Englisch. Dashboard, Zeitraum und Einstellungen mit unverändert sichtbarem Gemini-Anbieter wurden auf Deutsch und Englisch geprüft. Beim Wechsel von Deutsch zu Englisch blieb die App nutzbar. Die ursprüngliche App-Spracheinstellung wurde nach den Checks wiederhergestellt.
- Die bereits importierten Qualcomm-Modelle wurden wiederverwendet. Je Gerät und Sprachpaar waren zwei lokale Läufe erfolgreich. Eine synthetische deutsche Aufnahme (8,427 s) wurde mit englischer App-Sprache als Deutsch erkannt, eine synthetische englische Aufnahme (7,815 s) mit deutscher App-Sprache als Englisch. In jedem Lauf wurde die native Sitzung im selben Prozess wiederverwendet. Die Fixtures prüfen Local-STT ohne Mikrofon, Cloud oder produktive Statistikschreibvorgänge.

## Offene manuelle Prüfungen

- Live-Diktat über das Mikrofon bis in eine Ziel-App mit beiden Oberflächensprachen. Cloud-STT und Cloud-Rewrite mit englischem Diktat sind für 0.13 noch nicht auf Geräten abgenommen.
- Live-Sprachwechsel bei laufender 0.13-Overlay-Blase, Modusfächer, Vordergrundbenachrichtigung und TalkBack. Der ADB-Overlayversuch lieferte mit der parallel aktiven Altinstallation keinen belastbaren Nachweis für 0.13.
- Android 10–12, weitere Systemdialoge und vollständige Geräteabnahme der freien APK.
- Erneuter nativer HTP-Nachweis für Encoder und Decoder auf der signierten APK. Die Local-Fixtures prüfen Funktionsfähigkeit und Modell-Wiederverwendung.

## Rewrite und Diktiersprache

Die drei bestehenden Rewrite-Prompts verlangen, dass der Inhalt erhalten bleibt. Plus verlangt zusätzlich, Wortwahl und Satzreihenfolge möglichst beizubehalten. Chat und Formal erlauben stilistische Umformulierungen. Keiner der Prompts verlangt eine Übersetzung oder benennt ausdrücklich die Sprache des Diktats als Ausgabesprache. Englisch ist daher mit den bestehenden Prompts grundsätzlich möglich, die Sprachtreue ist jedoch nicht für jede Modellantwort abgesichert. Das gilt besonders bei Chat und Formal. Ein eigener englischer Cloud-Rewrite-Praxistest bleibt offen.

0.13 reicht die Oberflächensprache nicht an STT oder Rewrite weiter. Provider, Modelle und Prompts einschließlich Geminis festem `de-DE`-Hinweis sind gleich geblieben. Die Statistikberechnungen, Datenbankstruktur, SharedPreferences, Keystore-Aliase, gespeicherten Moduskennungen, Blasenposition und Modellpfade werden ohne Migration weiterverwendet.

## Veröffentlichung

Zuerst den 0.12-Kandidaten `89a9a9c` nach den verbliebenen Freigaben separat bauen, prüfen und als v0.12 veröffentlichen. Die Wiederherstellung der verschlüsselten Keystore-Sicherung ist im [0.12-Protokoll](RELEASE_0.12_VERIFICATION.md) als erledigt dokumentiert. Anschließend den separaten 0.13-Commit mit derselben Signatur veröffentlichen. Es wurden keine Tags erstellt oder geändert und keine Commits oder Releases auf GitHub veröffentlicht.
