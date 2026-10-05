# Qualcomm Local STT: Herkunft und Distribution

Prüfstand: 5. Oktober 2026. Diese Bestandsprüfung gilt für VoiceAI ASR Community
2.7.1.0 und QAIRT 2.50.0.260828 sowie die in TapStop fest eingetragenen Modelle.
Ein Versionswechsel braucht eine neue Prüfung der tatsächlich gelieferten Dateien.

## Entscheidung für dieses Repository

Im öffentlichen Quellbaum liegen eigene Integration, Buildskripte, kleine
Targetdefinitionen und SHA-256-Manifeste. **Keine Qualcomm-JAR/SO, Skeletons,
Gewichte, ONNX-Dateien, DLCs oder Context-Binaries werden eingecheckt.**
Ein Standardbuild benötigt kein Qualcomm-SDK. Die lokale Laufzeit wird optional
über `-PqualcommRuntimeDir=...` aus einem separaten, nicht versionierten Verzeichnis
in eine APK übernommen. Die Modelle werden separat in den App-Speicher importiert.

Die mit VoiceAI und QAIRT gelieferten AI-Stack-Lizenzen erlauben in Abschnitt 1(iv)
die Weitergabe von Objektcode als Bestandteil einer eigenen Anwendung. Sie
erlauben ausdrücklich keine selbständige Weitergabe des SDKs. Daher ist ein
integrierter APK-Build von einer Sammlung einzeln veröffentlichter SDK-Dateien zu
unterscheiden. Die Erlaubnis gilt unter den übrigen Vertragsbedingungen,
einschließlich der unveränderten Copyright-/Drittlizenzhinweise; sie ist keine
Open-Source-Lizenz für diese Laufzeitdateien.

## Tatsächlich geprüfte Lizenzquellen

| Quelle innerhalb der offiziellen Distribution | SHA-256 | Befund |
| --- | --- | --- |
| VoiceAI `Qualcomm AI Stack Proprietary License.pdf` | `1c5471e8087d32e2c3a30902d421c65c61093c96c3cf7b3de31dae8af9cc96c5` | Drei Seiten vollständig geprüft; eigene Entwicklung/Kopien und integrierter Objektcode erlaubt, Standalone-Distribution ausgeschlossen. |
| VoiceAI `whisper_sdk/LICENSE` | `64cb3f7075993865937228afd923dedb3f08df9492200c4811d042b1f8f53fdc` | Apache-2.0-Attribution; verweist für die Softwarebedingungen ausdrücklich auf NOTICE. |
| VoiceAI `whisper_sdk/NOTICE` | `b7bc294369c237ed02a8d1f2dc5d4551a97c0ef94c33c60a42964860d8603b1e` | Qualcomm-Vertragsvorrang sowie OpenAI-MIT- und Hugging-Face/EleutherAI-Apache-Hinweise. |
| QAIRT `LICENSE.pdf` | `ec1dccfdcba5c6e64126e84199b8362bf4999107bfa567ebe831dbb4c461692b` | Vollständiger extrahierter Text identisch mit der VoiceAI-AI-Stack-Lizenz; PDF-Dateien selbst verschieden. |
| QAIRT `NOTICE.txt` und `QNN_NOTICE.txt` | jeweils `0c5e8aad3506d0ab881cabaf0dae8de64e1784dc1ee6c873caf24a78fbf01924` | Beide Dateien byteidentisch, jeweils 130.445 Bytes; vollständig unverändert in lokale Runtime-APKs übernehmen. |
| Öffentliche QAI-Hub-Quellen, Commit `671590e9e5c3121e3c1ef693444f0787638d6951`, `LICENSE` | `1935f1927570e99e49d0f2f48b3d1e918c1a78d65d4d6f00e89c7c0bf9d4ddaf` | BSD-3-Clause; gilt für diese öffentlichen Quellen, nicht automatisch für VoiceAI oder QAIRT. |

Die QAIRT-Lizenzdateien wurden aus dem selben offiziellen 2.50.0.260828-Paket wie
die Laufzeitobjekte gelesen. Die [Qualcomm-Software-Center-Seite](https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community)
ist der offizielle Bezugspunkt; dort die konkrete geprüfte Version auswählen.
VoiceAI wird über den [Qualcomm Package Manager](https://qpm.qualcomm.com/)
bezogen. Die öffentliche [Qualcomm-Modellseite](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo)
verweist ebenfalls auf diesen VoiceAI-Bezug. Ein neueres angebotenes SDK ersetzt
die validierte Version nicht automatisch.

## Datei-Inventar und Entscheidungen

„APK: ja, bedingt“ bedeutet die ausdrückliche Objektcode-Erlaubnis unter den
vollständigen AI-Stack-Bedingungen und Erhalt aller mitgelieferten Hinweise.
„Git: nein“ ist die konservative Entscheidung gegen eine eigenständige
Veröffentlichung proprietärer Dateien im Quellbaum. Die exakten Größen und
Hashes stehen in [runtime-files.json](../tools/qualcomm-whisper/runtime-files.json).

| Artefakt | Herkunft | Lizenzbasis | Öffentliches Git | Integrierte APK | Entscheidung |
| --- | --- | --- | --- | --- | --- |
| `whisper-sdk.jar` | VoiceAI `whisper_sdk/libs/npu/rpc_libraries/android/whisper_all_quantized/` | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | Lokal bereitstellen; unverändert einbinden. |
| `libwhisperfunction_jni.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | Lokal einbinden. |
| `libwhisperfunction.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | Lokal einbinden. |
| `libwhisper_lib.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | Lokal einbinden. |
| `libfft.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise; nicht aus dem Dateinamen eine separate Lizenz ableiten | Nein | Ja, bedingt | Lokal einbinden. |
| `libdnnvad.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | SDK-VAD, lokal einbinden. |
| `libnnvad_model.so` | VoiceAI `whisper_sdk/libs/npu/rpc_libraries/assets/arm64-v8a_android/` | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | SDK-VAD-Gewichte, lokal einbinden. |
| `libopencc.so` | VoiceAI, dieselbe Variante, ARM64 | Geliefertes SDK-Objekt; keine vollständige komponentenspezifische Freigabe für eine eigenständige Neuverteilung nachgewiesen | Nein | Ja, bedingt als unverändertes SDK-Objekt | Nicht mit einer separat bezogenen OpenCC-Bibliothek gleichsetzen. |
| `libopencc_jni.so` | VoiceAI, dieselbe Variante, ARM64 | AI Stack + SDK-Hinweise | Nein | Ja, bedingt | Durch SDK-Ladeweg benötigt; keine Wörterbücher mitliefern. |
| `libQnnHtp.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | Gemeinsame HTP-Laufzeit. |
| `libQnnSystem.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | Gemeinsame Laufzeit. |
| `libQnnCpu.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | SDK-VAD; kein Whisper-CPU-Fallback. |
| `libQnnHtpPrepare.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Nicht enthalten | 85.026.120 Bytes; aus dem APK-Staging ausgeschlossen. Erfolgreiche TapStop-SDK-Sessions ohne diese Datei sind auf SM8650/V75 und SM8845(P)/V81 mit bestätigter Encoder-/Decoder-Ausführung nachgewiesen. Für die geprüften fertigen Contexts ist die Compile-/Prepare-Komponente zur Laufzeit entbehrlich; dieser Nachweis ersetzt keine vollständige Mikrofon-/Produktabnahme. |
| `libQnnHtpV75Stub.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | Nur SM8650/V75. |
| `libQnnHtpV81Stub.so` | QAIRT `lib/aarch64-android/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | Nur SM8845(P)/V81. |
| `libQnnHtpV75Skel.so` | QAIRT `lib/hexagon-v75/unsigned/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | DSP-Asset; keine ARM64-jniLib. |
| `libQnnHtpV81Skel.so` | QAIRT `lib/hexagon-v81/unsigned/` | AI Stack + QAIRT/QNN-Hinweise | Nein | Ja, bedingt | DSP-Asset; keine ARM64-jniLib. |
| `libcdsprpc.so` | Vorinstallierte System-/Vendor-Library des Geräts | Betriebssystembestand, keine Weitergabe geprüft | Nein | Nein | Nur Systembibliothek laden, nie kopieren. |
| S24 `encoder.bin`, `decoder.bin` | Offizielles Qualcomm `voice_ai`-Paket für SM8650, QAIRT 2.50 | Modellkarte nennt Apache-2.0 und verweist für Originalimplementierung auf OpenAI MIT; Runtime-Lizenz gilt separat | Hier nein | Nicht enthalten | Nur lokal importieren; keine pauschale Freigabe einer eigenen Modellspiegelung. |
| OpenAI-Ausgangsgewichte | `openai/whisper-large-v3-turbo`, Revision `41f01f3fe87f28c78e2fbf8b568835947dd65ed9` | MIT laut gepinnter OpenAI-Modellkarte | Unter MIT mit Hinweisen grundsätzlich erlaubt, hier nicht enthalten | Nicht enthalten | Vom Originalanbieter selbst beziehen. |
| QAI-Hub-Modellanpassungen | Öffentlicher Commit `671590e9…` | BSD-3-Clause | Mit Lizenz/Hinweisen erlaubt; hier nicht vendort | Nicht benötigt | Das Werkzeug lädt genau drei unveränderte Quelldateien und LICENSE in den lokalen Arbeitsbereich. |
| Selbst erzeugte SM8845 `encoder.bin`, `decoder.bin` | OpenAI → öffentliche Anpassungen → QAIRT-DLC → Context 97/V81 | MIT-/BSD-Ausgangsbasis; keine abschließende Freigabe des separat weitergegebenen konkreten Compileroutputs festgestellt | Hier nein | Nicht enthalten | Lokalen Import und reproduzierbaren Build anbieten; nicht als Release-Asset veröffentlichen. |
| `vocab.bin` | Gepinnte OpenAI-Tokenliste, reproduzierbar nach öffentlichem Format | OpenAI MIT; öffentliche Konvertierung in QAI Hub BSD-3-Clause | Mit relevanten Hinweisen grundsätzlich möglich; hier nicht enthalten | Nicht enthalten | Lokal erzeugen/importieren; SHA-256 fest gepinnt. |
| ONNX, External Data, DLC, Compilelogs | Eigener Build und SDK-Zwischenprodukte | Abgeleitete Modelle und SDK-Ausgaben; gesonderte Prüfung für Veröffentlichung | Nein | Nein | Ausschließlich lokaler Arbeitsbereich. |

Die offene Ausgangslizenz eines Modells wird hier ausdrücklich anerkannt. Die
konservative Entscheidung zu den kompilierten Modellpaketen ist **keine
Behauptung**, dass jeder QAIRT-Compileroutput automatisch proprietär wäre. Es
fehlt die vollständige Freigabe für die eigenständige Veröffentlichung genau
dieser Pakete mitsamt möglichen eingebetteten Runtimebestandteilen und
erforderlichen Hinweisen. Das lokale Erzeugen/Verwenden ist davon zu unterscheiden.

## Hinweise in einer Runtime-APK

[stage-runtime.py](../tools/qualcomm-whisper/stage-runtime.py) prüft Größen und
Hashes aller benötigten Originalobjekte und der sechs Lizenzdateien. Es kopiert
ausschließlich die feste Liste in ein neues lokales Verzeichnis. Die APK erhält
unter `assets/notices/`:

- `VoiceAI-AI-Stack-License.pdf`
- `VoiceAI-LICENSE.txt`
- `VoiceAI-NOTICE.txt`
- `QAIRT-LICENSE.pdf`
- `QAIRT-NOTICE.txt`
- `QNN-NOTICE.txt`

Die Hinweise bleiben unverändert und gehören zu jeder weitergegebenen
Runtime-APK. Sie ändern die Lizenz des TapStop-Quellcodes nicht; umgekehrt
lizenziert TapStop die Qualcomm-Dateien nicht unter einer Projektlizenz um.
Native Bibliotheken werden nicht gestript, damit die geprüften Hashes erhalten
bleiben. Nicht benötigte SDK-Tools, Header, Beispiele und andere Backends werden
nicht mitgepackt. Ein gegebenenfalls später veröffentlichter APK-Build muss sein
konkretes Inventar erneut gegen diese Liste prüfen.

## Öffentliche Primärquellen

- [OpenAI Whisper MIT-Lizenz](https://github.com/openai/whisper/blob/main/LICENSE).
- [Gepinnte OpenAI-Large-V3-Turbo-Modellkarte mit MIT-Angabe](https://huggingface.co/openai/whisper-large-v3-turbo/blob/41f01f3fe87f28c78e2fbf8b568835947dd65ed9/README.md).
- [BSD-3-Clause-Lizenz der verwendeten öffentlichen Qualcomm-Quellen](https://github.com/qualcomm/ai-hub-models/blob/671590e9e5c3121e3c1ef693444f0787638d6951/LICENSE).
- [Öffentliche Whisper-Konvertierung für VoiceAI](https://github.com/qualcomm/ai-hub-models/blob/671590e9e5c3121e3c1ef693444f0787638d6951/src/qai_hub_models/models/templates/hf_whisper/utils.py).
- [Qualcomm-Modellangebot und Original-Downloads](https://huggingface.co/qualcomm/Whisper-Large-V3-Turbo).

Die konkreten SDK-Verträge stammen aus den oben gehashten Originaldistributionen;
eine Produktwebseite oder ein Dateiname ersetzt diese Texte nicht. Die
[Buildanleitung](../tools/qualcomm-whisper/README.md) enthält keine eingebetteten
Zugangsdaten und veröffentlicht keine privaten Forschungsaufnahmen.
