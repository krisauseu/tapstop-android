# Lokale Qualcomm-Spracherkennung in TapStop

TapStop 0.9 ergänzt **Lokal – Qualcomm NPU** als dritten STT-Provider. Das Backend verwendet Whisper Large V3 Turbo FP16 mit VoiceAI ASR Community Edition 2.7.1.0, QAIRT/QNN 2.50.0.260828 und dem Hexagon HTP. Beide folgenden Targets wurden nach der Forschung auch in TapStop mit echten Roh- und Plus-Diktaten, direkter Texteingabe und nativen HTP-Nachweisen geprüft. Die [Geräteabnahme](../DEVICE_TESTS.md) enthält Messwerte und die bekannte SDK-Grenze bei bestimmten Aufnahmen nahe 30 Sekunden.

## Architektur

```text
OverlayService
  -> AudioRecording: AAC für Cloud / PCM16-WAV für Local
  -> SpeechToTextProvider
       OpenAI / Groq / QualcommRuntime
  -> SttResult: Rohtext, Sprache soweit verfügbar, STT-Zeit, Provider, Modell
  -> DictationProcessor
       Roh: unverändertes STT-Ergebnis
       Plus / Chat / Formal: separat konfigurierter Cloud-Rewrite
  -> bestehende Texteingabe / Clipboard-Fallback / UsageStats

QualcommRuntime
  -> VoiceAI Whisper SDK
  -> QNN HTP Backend
  -> System-FastRPC / DSP-HAL
  -> CDSP / Hexagon HTP V75 oder V81
```

Der STT-Vertrag steht in `SpeechToTextProvider.kt`. `QualcommTargets.kt` beschreibt die Hardware- und Modellkombinationen, `QualcommModelStore.kt` verwaltet den Bestand, `QualcommRuntime.kt` verbindet das optionale SDK und `QualcommSession.kt` serialisiert native Sitzungen. `AudioRecording.kt` stellt das lokale PCM-Format bereit. Weder Aufnahme noch Overlay enthalten gerätespezifische S24-/MagicPad-Zweige.

Der STT-Provider wird **beim Aufnahmestart** festgelegt. Damit können ein späterer Settings-Wechsel weder das Aufnahmeformat noch das Ziel der Audioverarbeitung verändern. Modus, Rewrite-Provider, Keys und Cloudmodell werden am Aufnahmeende übernommen. Historische Cloudinstallationen behalten ohne zusätzliche Auswahl die bisherige STT-/Rewrite-Paarung. Für Local wird standardmäßig der zuletzt ausgewählte Cloudprovider für Rewrite verwendet; OpenAI und Groq können auch ausdrücklich gewählt werden.

Roh ruft keinen Rewrite auf. Bei Local + Roh sendet TapStop weder Audio noch Transkript an eine Cloud. Bei Local + Plus/Chat/Formal bleibt STT lokal und der Text geht anschließend an den konfigurierten Rewrite-Provider. Die UI bezeichnet deshalb **Spracherkennung** als lokal und verspricht vollständige lokale Verarbeitung nur für Roh.

## Validierte Targets und Hardwareerkennung

| Target-ID | Akzeptierte `ro.soc.model`-Werte | QNN `socModel` | HTP | Konkret validiertes Gerät |
| --- | --- | ---: | --- | --- |
| `sm8650-v75` | `SM8650` | 57 | V75 | Samsung Galaxy S24 Ultra, `SM-S928B` |
| `sm8845-v81` | `SM8845`, `SM8845P` | 97 | V81 | HONOR MagicPad 4, `YLE-W09` |

SM8650 entspricht dem hier geprüften Snapdragon 8 Gen 3, SM8845(P) dem Snapdragon 8 Gen 5. Snapdragon **8 Elite Gen 5** ist eine andere Familie und keine alternative Bezeichnung für SM8845. Insbesondere ist ein SM8850/SoC-87/V81-Paket nicht für SM8845/SoC-97 freigegeben. Gleiche HTP-Architektur und erfolgreiches Modellladen allein belegen keine ausführbare Whisper-Pipeline.

Die App liest ausschließlich Systeminformationen: `ro.soc.model`, `ro.soc.manufacturer`, ergänzend Androids SoC-Felder, `Build.MANUFACTURER`, `Build.MODEL`, unterstützte ABIs sowie das Vorhandensein der Systembibliothek `libcdsprpc.so`. Qualcomm/QTI und `arm64-v8a` sind erforderlich. Die endgültige native Verfügbarkeit wird bei der Vorbereitung durch Androids Library-Namespace und tatsächliches SDK-/QNN-Init geprüft. Seriennummern werden nicht gelesen oder gespeichert.

SoC ist die primäre Zuordnung; der Gerätename entscheidet nur darüber, ob genau diese Kombination bereits praktisch getestet wurde. Es gibt keine unscharfe Marketingnamen-Zuordnung und keine frei erfundenen SoC-Aliase.

| Interner Zustand | Bedeutung |
| --- | --- |
| `SUPPORTED_VALIDATED` | Bekannter SoC und konkret geprüftes Hersteller-/Gerätemodell |
| `SUPPORTED_SOC_UNVALIDATED_DEVICE` | Passendes bekanntes SoC-Target, anderes noch nicht geprüftes Gerätemodell; sichtbar gekennzeichnet |
| `UNSUPPORTED` | Kein freigegebenes Target oder Qualcomm-Identität nicht bestätigt |
| `RUNTIME_UNAVAILABLE` | ARM64/FastRPC oder passende optionale SDK-Runtime fehlt |
| `MODEL_MISSING` | Modellordner, Manifest oder notwendige Dateien fehlen |
| `MODEL_INVALID` | Target, Metadaten, Dateigröße oder SHA-256 stimmen nicht |

Unbekannte Targets bleiben in der UI sichtbar, aber deaktiviert. Andere Geräte mit einem bekannten SoC werden nicht automatisch als am Gerät validiert bezeichnet. Auch ein getestetes Gerätemodell ist keine Garantie für jede zukünftige Firmware-/SDK-Kombination.

## Non-Root-Runtime

Die normale App verwendet die vom System bereitgestellte `libcdsprpc.so`. Sie wird im Manifest als optionale native Systembibliothek deklariert, damit Cloudprovider auch auf Geräten ohne FastRPC installierbar bleiben. Die App schreibt weder nach `/vendor` noch in andere Systemverzeichnisse. Root, Bootloaderänderungen, Remounts und SELinux-Anpassungen sind nicht erforderlich.

Der passende unsigned DSP-Skeleton wird aus dem optionalen SDK-Asset in den App-Speicher kopiert und per SHA-256 geprüft. Vor dem einmaligen Init werden prozesslokal gesetzt:

```text
ADSP_LIBRARY_PATH = <app-skeleton-dir>;<nativeLibraryDir>;/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp
DSP_LIBRARY_PATH  = <app-skeleton-dir>;<nativeLibraryDir>;/vendor/lib/rfsa/adsp;/vendor/dsp;/dsp
```

Der DSP-Pfadseparator ist ein Semikolon. Systempfade werden nur gelesen. Die VoiceAI-Überladung `init(cdspPath, vadPath, modelPaths...)` erhält den App-Skeleton-Pfad und die absoluten Pfade zu Vocab, Encoder und Decoder. Der reguläre FastRPC-/HAL-Weg öffnet den App-Skeleton auf CDSP Domain 3.

Gemeinsame ARM64-Komponenten des validierten Builds:

- VoiceAI: `libwhisperfunction_jni.so`, `libwhisperfunction.so`, `libwhisper_lib.so`, `libfft.so`, `libdnnvad.so`, `libnnvad_model.so`, `libopencc.so`, `libopencc_jni.so` und die SDK-JAR.
- QAIRT: `libQnnHtp.so`, `libQnnSystem.so`, `libQnnCpu.so`. Die Compile-/Prepare-Library ist nicht in der Runtime-APK enthalten; die Geräteprüfungen verwenden fertige Contexts.
- Targetabhängig: `libQnnHtpV75Stub.so` / `libQnnHtpV81Stub.so` sowie `libQnnHtpV75Skel.so` / `libQnnHtpV81Skel.so`.

Skeletons sind DSP-Code und liegen als Assets vor, nicht als ARM64-`jniLibs`. `libQnnCpu.so` gehört zur bestehenden SDK-VAD-Komponente; auch Audio-/Mel-Verarbeitung und Tokensteuerung haben CPU-Anteile. Das Backend hat **keinen Whisper-CPU-Fallback**. Der NPU-Nachweis betrifft die tatsächlichen Whisper-Encoder-/Decodergraphen auf HTP, nicht eine vollständig CPU-freie App.

SHA-256-Pins der unveränderten Bibliotheken und Skeletons stehen im Runtimecode und im [Runtimeinventar](../tools/qualcomm-whisper/runtime-files.json). Die Java-SDK-Angabe `2.0.0` bezeichnet die interne API-Version, nicht die Community-Paketversion 2.7.1.0. Die Modell-Build-ID `2.50.0.260828221209` gehört zum verwendeten QAIRT-2.50-Stand.

## Prozessvertrag und Fehler

Die Forschungsversuche belegen eine relevante SDK-Grenze: **Native `deInit → init` ist innerhalb desselben Android-Prozesses nicht zuverlässig unterstützt.** Ein neues Java-Objekt und selbst derselbe Worker beseitigen diese Grenze nicht.

TapStop hält deshalb genau einen `QualcommRuntime`-Owner mit Application-Context pro Prozess. Ein serieller Worker führt die Initialisierung und die STT-Sitzungen aus. Activity-Neuerstellung, Overlay-Ende und Providerwechsel rufen kein `deInit` auf. Vor und nach jedem Diktat erfolgt `whisper.stop()` auf dem Worker; Streams und einzelne Sitzungen werden geschlossen. Cleanup wird nicht aus dem nativen Callback-Thread ausgeführt.

Ein erfolgreicher Init wird für weitere Diktate wiederverwendet. Ein fehlendes oder ungültiges Modell wird vor dem nativen Init abgewiesen und kann importiert/repariert werden. Nach einem tatsächlich gestarteten, fehlgeschlagenen Init oder einem nicht mehr antwortenden SDK erfolgen keine weiteren nativen Init-Versuche im selben Prozess. Die App zeigt einen Fehler mit Neustarthinweis. Sie beendet den Prozess nicht automatisch und wählt weder CPU noch Cloud als Ersatz.

Native Modellressourcen bleiben resident, solange Android den App-Prozess hält. Das ist der gewählte Lifecycle-Vertrag, keine Aussage über einen behobenen SDK-Teardownfehler. Ein zukünftiges explizites Entladen mit erneuter Nutzung benötigt eine Qualcomm-Korrektur oder einen separat validierten Providerprozess.

Vorbereitung und große Hashprüfungen laufen auf Hintergrundthreads. Vor der ersten lokalen Aufnahme erscheint ein Vorbereitungszustand; die Aufnahme beginnt erst nach erfolgreichem Init. Die PoCs beobachteten ungefähr 1–5 Sekunden native Initialisierung. Die zusätzliche Prüfung der über 2 GB großen Dateien kostet weitere Zeit. Diese einmaligen Kosten gehören nicht zur STT-Latenz und sind keine Zeitgarantie.

SDK-Fehlercodes, darunter `INVALID_SPEECH_TOKEN`, werden als Fehler behandelt. Ein teilweise vorliegender Text nach einem fehlerhaften STT-Lauf wird nicht als erfolgreiches Gesamtdiktat übernommen. Cloudprovider bleiben manuell auswählbar; es gibt keinen automatischen Audio-Upload nach einem lokalen Fehler.

### Bekannter VoiceAI-Fehler am Aufnahmeende

VoiceAI 2.7.1.0 kann bei bestimmten Aufnahmen nahe 30 Sekunden einen finalen Callback mit dem exakten Text `SPECTROGRAM FAIL` und dennoch `code=0` liefern. Das wurde auf dem MagicPad mit einem echten Diktat und zweimal mit einer synthetischen 29,98-s-Probe reproduziert. Die nativen Logs zeigen dabei nach einem gültigen Abschnitt einen zusätzlichen Verarbeitungsversuch mit leerem Restpuffer. Der interne SDK-Fehler ist damit beobachtet, aber nicht durch TapStop behoben.

Der gepinnte native SDK-Bestand enthält diesen vollständigen Fehlerwert. `QualcommSession` behandelt ausschließlich diesen exakten, gegebenenfalls von Leerraum umgebenen Callback als STT-Fehler. Frühere Teilsegmente werden für dieses Diktat verworfen; es erfolgt weder eine Einfügung noch ein Rewrite. Normale Sätze, in denen diese Wörter vorkommen, bleiben unverändert. Die Callback-Replay-Regression ging vor der Korrektur rot. Der abschließende Gerätetest reproduziert den SDK-Fehler auf beiden Targets und bestätigt jeweils die Fehlerabweisung ohne Rewrite/Teiltext sowie eine ausdrücklich gestartete erfolgreiche Folgesitzung mit derselben Runtime.

Dies ist kein automatischer Wiederholungsversuch, kein Cloud-/CPU-Fallback und keine Änderung der SDK-VAD. Das 30-s-Aufnahmelimit bleibt bestehen; es gibt keine pauschale Erfolgszusage für jede Aufnahme bis zu dieser Grenze. Ein sichtbarer SDK-Fehler kann ein erneutes, kürzeres Diktat erfordern. Die Geräteprotokolle unterscheiden diesen Fehlerlauf von erfolgreich abgenommenen Diktaten.

## Aufnahmeformat und 30-Sekunden-Grenze

Local zeichnet PCM16, Mono, 16 kHz auf. Ein 44-Byte-RIFF/WAV-Header wird beim Abschluss korrekt geschrieben; der SDK-`FileInputStream`-Pfad überspringt genau diese 44 Bytes. Die Runtime validiert Format, Dateilänge und Header vor der Transkription.

Bei 480.000 Samples beziehungsweise 960.000 PCM-Bytes endet die Aufnahme spätestens nach **30 Sekunden**. Ein UI-Autostopp ergänzt die Samplegrenze; ein weiterer Tap kann vorher stoppen. Das vollständige Audio wird genau einmal über `whisper.start(stream)` übergeben. Es gibt kein eigenes Long-Form-Chunking, keine Pause-Schnittsuche und keine Unterdrückung SDK-interner VAD-Segmente.

VoiceAI darf innerhalb eines Diktats mehrere Encoder-/Decoderläufe ausführen. Ein App-Aufruf bedeutet ausdrücklich nicht einen einzigen internen Graphlauf. Die experimentellen 30–60-Sekunden-PoC-Versuche hatten unzureichende Qualität an Chunkgrenzen und sind für TapStop nicht freigegeben. Cloudaufnahme und Cloudlimits werden durch diese lokale Grenze nicht verkürzt.

## Modellbestand und Import

Die Gewichte sind nicht in der APK und werden nicht automatisch heruntergeladen. Die erste öffentliche Integration unterstützt einen ausdrücklichen lokalen Ordnerimport über Androids Storage Access Framework. Dafür sind keine allgemeinen Speicherberechtigungen notwendig. Ein späterer Download kann denselben Paket-/Prüfvertrag bedienen; aktuell ist keine automatische Downloadquelle hinterlegt.

Ein importierbares Verzeichnis enthält:

```text
tapstop-model.json
encoder.bin
decoder.bin
vocab.bin
```

`tapstop-model.json` nennt Schema, Target-ID, Paket-ID und -Version, Modell, FP16, QNN-`soc_model`, HTP-Version, VoiceAI-/QAIRT-Versionen und für jede Datei Bytezahl und SHA-256. Die autoritativen Werte kommen aus dem kompilierten Targetregister; ein editiertes Manifest kann fremde Gewichte nicht freigeben. Die Quellmetadaten `config.json` und `metadata.json` aus den PoCs bleiben Herkunftsbelege, ersetzen aber nicht diesen Importvertrag.

| Target | Encoder-Bytes | Decoder-Bytes | Vocab-Bytes |
| --- | ---: | ---: | ---: |
| SM8650 / 57 / V75 | 1.752.566.520 | 452.486.656 | 357.313 |
| SM8845(P) / 97 / V81 | 1.752.666.608 | 452.449.800 | 357.313 |

Die bestätigten Hashes stehen in [model-packages.json](../tools/qualcomm-whisper/model-packages.json) und `QualcommTargets.kt`. Das S24-Paket ist das final validierte **VoiceAI**-Paket; ein zusätzliches `qnn_context_binary`-Paket hat einen anderen Encoderhash. Das MagicPad-Paket ist der erfolgreiche eigene SoC-97-Build, niemals das zuvor fehlgeschlagene fremde SoC-87-Paket.

Aus einem bereits lokal vorhandenen, bestätigten Paket erzeugt das Werkzeug einen neuen Importordner. Beispiel mit selbst gewählten lokalen Pfaden:

```sh
python3 tools/qualcomm-whisper/make-model-package.py \
  --target sm8845-v81 \
  --source /path/to/validated-model \
  --output /path/to/new-import-folder
```

Für S24 wird `--target sm8650-v75` verwendet. Das Werkzeug prüft Quell- und Kopiedateien, verändert die Quellen nicht und schreibt das Manifest zuletzt. Ein neu kompilierter, anders gehashter Build wird nicht automatisch akzeptiert.

Den fertigen Ordner auf das Gerät übertragen und in TapStop unter **Lokal – Qualcomm NPU → Modellordner importieren** auswählen. Danach **Modell prüfen und vorbereiten** verwenden. Der Import prüft nur die vier festgelegten Dateinamen, begrenzt Manifest und Dateiinhalt, hasht beim Kopieren und verwirft unvollständige Staging-Verzeichnisse. Ein falsches Target, fehlende Dateien, abweichende Größe oder SHA-256 verhindern den Start.

App-Speicherlayout:

```text
<external-files>/qualcomm-models/
  <package-id>-<version>/                   # optionaler Entwickler-Transfer
  <package-id>-<version>-<generation>/      # importierte unveränderliche Generation
  <package-id>.current                     # atomar geschalteter Generationsname
<external-files>/qualcomm-runtime/<qairt-version>/v<htp>/
  libQnnHtpV<htp>Skel.so
```

Ein Import erstellt eine neue Generation und schaltet erst nach erfolgreicher Prüfung den kleinen Pointer atomar um. Bereits von einer nativen Runtime verwendete Dateien werden weder überschrieben noch gelöscht. Die Runtime behält ihren geprüften Modellpfad bis zum Prozessende. Vor Init werden vollständige Hashes geprüft; während des residenten Betriebs werden Datei-Größen und Änderungszeiten überprüft. Änderungen an aktiven Dateien führen zu einem Fehler.

Diese Erhaltung hat einen Speicherpreis: Eine Reparatur benötigt Platz für eine weitere vollständige Kopie; ältere Generationen bleiben erhalten. Es gibt derzeit keine automatische Bereinigung alter Generationen. Ein unverändert gültiger Bestand wird beim erneuten Import wiederverwendet. App-Updates erhalten Modelle; Deinstallation und Löschen der App-Daten entfernen sie.

## Optionaler SDK-Build und Lizenzen

Der öffentliche Quellcode baut ohne Qualcomm-Binaries. Auf kompatibler Hardware erklärt die UI dann, dass die APK keine lokale Runtime enthält. Für einen lokal lizenzierten SDK-Build wird ein zusätzliches Verzeichnis bereitgestellt:

```text
<runtime-bundle>/
  libs/*.jar
  jniLibs/arm64-v8a/*.so
  assets/qualcomm/libQnnHtpV75Skel.so
  assets/qualcomm/libQnnHtpV81Skel.so
  assets/qualcomm/runtime-manifest.json
  assets/notices/...
```

```sh
./gradlew -PqualcommRuntimeDir=/path/to/local-runtime \
  assembleDebug assembleDebugAndroidTest testDebugUnitTest lint packageTapStop
```

`stage-runtime.py` übernimmt ausschließlich die gepinnten, benötigten Dateien und zugehörigen Notices aus lokal vorhandenen validierten SDK-/PoC-Beständen. Für Aufbau, Lizenzquellen, Dateiauswahl und Parameter siehe [Werkzeuge](../tools/qualcomm-whisper/README.md) und [Lizenzinventar](QUALCOMM_LICENSING.md). Bibliotheken, Skeletons, SDK-ZIPs und Modelle werden nicht in Git aufgenommen. Eine lokal erzeugte APK ist keine pauschale Erlaubnis zur öffentlichen Weitergabe aller enthaltenen Artefakte.

Die Modellgewichte, ONNX External Data, DLCs und finalen QNN-Contexts bleiben außerhalb des öffentlichen Repositorys. Insbesondere wird das eigene SM8845-Paket nicht allein aufgrund seines erfolgreichen Builds veröffentlicht. Herkunft und Rechte von OpenAI-Gewichten, Qualcomm-Anpassungen, Tokenizer, Runtime und abgeleiteten Contexts werden getrennt im Lizenzinventar behandelt.

## Eigener SM8845-Build und reproduzierbarer Weg

Ein fehlendes offizielles Qualcomm-Whisper-Paket bedeutet nicht zwangsläufig, dass der SoC ungeeignet ist. Für SM8845 gelang in der Forschung dieser vollständige Weg:

```text
OpenAI Whisper Large V3 Turbo
  -> öffentliche Qualcomm-Modellanpassungen
  -> FP32-ONNX mit externen Gewichten
  -> offizieller ONNXScript-Optimizer
  -> QAIRT FP16 DLC
  -> QNN HTP Context, socModel 97 / dspArch V81
  -> direkte Encoder-/Decoder-Inferenz auf echter Hardware
  -> VoiceAI-End-to-End-STT und echte Mikrofonabnahme
```

Kein AIMET Pro, keine Quantisierung, keine Kalibrierung, keine GPU und kein Root waren für diesen erfolgreichen FP16-Weg erforderlich. Der ursprüngliche quantisierungsorientierte Qualcomm-Notebookweg mit AIMET Pro wurde dadurch nicht vollständig auf Open-Source-AIMET portiert; der erfolgreiche Export umgeht dessen nicht benötigten Roundtrip.

Die bereinigten, selbst entwickelten Werkzeuge stehen unter [tools/qualcomm-whisper](../tools/qualcomm-whisper/README.md):

1. `fetch-sources.py` und `source_pins.py`: gepinnte öffentliche Quellen und Gewichte separat beziehen. Forschungsreferenz: OpenAI-Revision `41f01f3fe87f28c78e2fbf8b568835947dd65ed9`, öffentliche Qualcomm-AI-Hub-v0.63.0-Anpassungen bei Commit `671590e9e5c3121e3c1ef693444f0787638d6951`.
2. `export-onnx.py` mit `export-requirements.txt`: lokale CPU-Exportumgebung, statische VoiceAI-Schnittstellen und offizieller ONNXScript-Optimizer. Der geprüfte Export verwendete Python 3.12, PyTorch 2.8, Transformers 4.56.2, ONNX 1.19.1, ONNXScript 0.4.0, ONNX-IR 0.1.7 und Protobuf 6.33.5.
3. `verify-onnx.py`: unabhängige ONNX-Runtime-Prüfung. Im Forschungsstand blieben Logitabweichungen klein und die geprüften Argmax-Tokens identisch; dies ersetzt keine Geräteabnahme.
4. `compile-contexts.py`: isoliertes Linux x86_64 mit lokal bezogener QAIRT 2.50.0.260828. `qairt-converter --float_bitwidth 16 --float_bias_bitwidth 16` und explizite FP16-IO-Konfiguration erzeugen DLCs; Token-/Positionsinputs bleiben INT32. Der Context-Generator nutzt `libQnnModelDlc.so`, das HTP-Backend und targetgebundene Konfiguration.
5. `whisper_contract.py` und das offizielle `qnn-context-binary-utility`: Backend 6, SoC, DSP-Architektur, Graphnamen, Tensorformen und Datentypen prüfen. Encoder: `hf_whisper_encoder`, Input `[1,128,3000]`; Decoder: `hf_whisper_decoder`, 19 Inputs und 9 Outputs, vier Decoderlayer.
6. `make-vocab.py`: gepinnte OpenAI-Tokenizerquelle verwenden. Das erzeugte Vocab war byteidentisch zum bestätigten offiziellen Vocab.

Für SM8845 lautet die Compilerkonfiguration `--soc-model 97 --dsp-arch 81`; für SM8650 `--soc-model 57 --dsp-arch 75`. Der ausgelieferte S24-Targeteintrag verwendet weiterhin die bestätigten offiziellen VoiceAI-Contexts. Ein eigener S24-Neubuild erhält durch diese Parameter allein keine Freigabe.

Ein früherer QAIRT-Encoder-Wrapper beendete sich nach dem Speichern mit Exit 134. Separate offizielle Context-Inspektion, Hash-/Schnittstellenprüfung und echte Hardwareausführung bestätigten das gespeicherte Artefakt anschließend. Die neue Pipeline wertet einen fehlgeschlagenen Prozess nicht stillschweigend als Erfolg. Inspektions- oder Hostfehler sind von realer Inferenz getrennt zu beurteilen.

Die Skripte enthalten keine proprietären SDK-Implementierungen und kopieren nicht das gesamte PoC. Ausführliche Forschungs-`RESULTS.md` und unbearbeitete Evidenz gehören zum separaten Projektarchiv. Persönliche Diktate, Geräteaudio, Seriennummern, Zugangsdaten und große Debuglogs gehören nicht ins Repository.

## Weitere Qualcomm-Targets

Neue SoCs können ergänzt werden, wenn QAIRT/QNN das konkrete Target unterstützt, passende HTP-Stub-/Skeleton-Runtime verfügbar ist und Encoder/Decoder für genau dessen `socModel`/`dspArch` erzeugt oder bezogen werden können. Die öffentliche Pipeline nimmt Targetparameter entgegen, deklariert ein Build-Ergebnis aber ausdrücklich als **noch nicht auf Hardware validiert**.

Vor einem neuen offiziell unterstützten Target sind erforderlich:

1. Korrekt erzeugte Contexts mit geprüften Hashes und passenden VoiceAI-Schnittstellen.
2. Unabhängig bestätigte `socModel`-/`dspArch`-Metadaten.
3. Erfolgreiche direkte QNN-Encoder-/Decoder-Tests auf echter Zielhardware.
4. Erfolgreiches VoiceAI-End-to-End-STT.
5. Positive native HTP-/FastRPC-Ausführungsnachweise für Encoder und Decoder.
6. Mindestens ein erfolgreiches echtes Mikrofon-Diktat und Prüfung wiederholter Sitzungen.

Erst danach werden ein datengetriebener `QualcommTarget`, überprüfte Modellhashes und die erforderlichen Runtimepins ergänzt. Es gibt keine pauschale Zusage für jede Snapdragon-8-Generation.

## Statistik, Diagnose und Geräteabnahme

Die bestehende SQLite-Tabelle bleibt auf Schema Version 1. Local speichert `provider=qualcomm`, `model=whisper-large-v3-turbo`, bisherigen Modus und `recording_ms`, `stt_ms`, `rewrite_ms`, `total_ms`, `success`. Bei Roh bleibt `rewrite_ms` leer. Erfolgreiche Diktate werden getrennt als Qualcomm NPU angezeigt; der Echtzeitfaktor bleibt **Summe Aufnahmezeit / Summe STT-Zeit**. Init und Aufnahmezeit sind nicht Bestandteil der STT-Messung.

Die lokale Settings-Diagnose zeigt Hersteller/Modell, SoC, ABI, Target, HTP, Modellstatus und Runtimezustand und kann auf ausdrücklichen Tap kopiert werden. Es gibt keine Diagnose-Telemetrie und keine Seriennummer. Der UI-Zustand „Runtime bereit“ ist allein kein Beweis für aktuelle Encoder-/Decoder-Ausführung. Der letzte NPU-Status bleibt ohne passenden Ausführungsnachweis unbestätigt.

Für die technische Geräteabnahme müssen native Logs das Öffnen des App-Skeletons, einen echten CDSP-Domain-3-Handle und dessen Verwendung während **Encoder und Decoder des jeweiligen Diktats** zeigen. Geladene ARM-Libraries, erfolgreiches Init oder niedrige Latenz allein genügen nicht. Ein fehlender Lognachweis wird nicht automatisch als CPU-Ausführung interpretiert.

Beobachtete Forschungswerte bei längeren Nutzerdiktaten waren auf dem S24 rund 0,997–1,202 s STT bei 23,22–30 s Audio; auf dem MagicPad rund 0,838–1,261 s bei 26,16–30 s. Das waren unterschiedliche Diktate und keine kontrollierte Gerätevergleichsstudie. Die Werte sind keine TapStop-0.9-Messung und keine Leistungs- oder Qualitätsgarantie.

Die TapStop-Geräteabnahme umfasst Update mit gleicher Signatur und erhaltenen Daten, automatische Zuordnung, Modellhashes, echtes Roh- und Rewrite-Diktat, Texteingabe in eine fremde App, Statistik, manuelle Cloudproviderwahl, mehrere aufeinanderfolgende Sitzungen und HTP-Belege. Offensichtliche Thermalfehler und residenter Speicher werden beobachtet, nicht durch Firmware- oder Taktänderungen umgangen. Forschungs-Apps dürfen erst nach erfolgreicher TapStop-Abnahme des jeweiligen Geräts entfernt werden; Forschungsartefakte und TapStops eigene Modellkopien müssen erhalten bleiben.
