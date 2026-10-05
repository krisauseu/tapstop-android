# Lokaler Whisper-FP16-Build für Qualcomm HTP

Diese kleinen Werkzeuge übernehmen den erfolgreichen lokalen SM8845-Buildweg aus
dem separaten Forschungs-PoC. Sie enthalten eigene Ablaufsteuerung und den
beobachteten Tensorvertrag, keine SDK-Binaries, Modellgewichte oder kopierten
proprietären Notebookquellen. Öffentliche Qualcomm-Modellanpassungen werden
unverändert und mit ihrem Lizenztext separat bezogen.

Der erfolgreiche Weg lautet:

```text
OpenAI Whisper Large V3 Turbo
  → öffentliche Qualcomm-SHA/Conv2D/Cache-Anpassung
  → PyTorch-ONNX-Export + offizieller ONNXScript-Optimizer
  → FP32-ONNX mit External Data
  → QAIRT FP16-DLC mit FP16-IO und INT32-Tokens/Positionen
  → QNN HTP Context für konkretes socModel/dspArch
  → unabhängige Context-Inspektion
  → reale QNN-, VoiceAI- und Mikrofontests
```

Für diesen FP16-Weg sind **kein AIMET Pro, keine Quantisierung, Kalibrierung,
GPU, AI-Hub-Anmeldung oder Root-Rechte** erforderlich. QAIRT und VoiceAI bleiben
separat lizenzierte Qualcomm-SDKs. Ihre Bedingungen und die
[Distributionsentscheidung](../../docs/QUALCOMM_LICENSING.md) gelten weiterhin.

## Was tatsächlich validiert ist

| SoC-Aliase | QNN `socModel` | `dspArch` | Modellherkunft | Echtgerät |
| --- | ---: | ---: | --- | --- |
| SM8650 | 57 | 75 | Offizielles Qualcomm-`voice_ai`-Paket | Galaxy S24 Ultra, SM-S928B |
| SM8845, SM8845P | 97 | 81 | Eigener FP16-Neubuild | HONOR MagicPad 4, YLE-W09 |

Der generische Compiler akzeptiert explizite Targetparameter. Das macht ein
neues Target noch nicht unterstützt. **SM8850 / 87 / V81 ist nicht das
SM8845-Paket**: das fremde Paket ließ sich auf SM8845 laden, seine erste
Encoder-Ausführung scheiterte. Gleiche HTP-Generation reicht nicht.

Ein neuer Build für SM8650 mit diesen Werkzeugen ist ebenfalls nicht allein
durch die erfolgreiche offizielle S24-Referenz validiert. Unterschiedliche
Compileroutputs bekommen neue Hashes und brauchen eine eigene Abnahme.

## Werkzeuge

| Datei | Aufgabe |
| --- | --- |
| `fetch-sources.py`, `source_pins.py` | Drei öffentliche Qualcomm-Python-Module und deren BSD-Lizenz herunterladen, SHA-256 prüfen. |
| `export-onnx.py` | Gepinnte OpenAI-Gewichte prüfen, Anpassungen anwenden, zwei Decoder-Schritte numerisch mit Original vergleichen, optimiertes ONNX exportieren. |
| `verify-onnx.py` | Separater ONNX-Runtime-Vergleich zweier Decoder-Schritte gegen die Originalgewichte. CPU nur zur Buildprüfung; kein App-Fallback. |
| `whisper_contract.py` | Vollständiger Encoder-/Decoder-Tensorvertrag sowie strenge Contextprüfung. |
| `compile-contexts.py` | FP16-DLC erzeugen, konkrete HTP-Contexts kompilieren und mit Qualcomm-Tool inspizieren. |
| `make-vocab.py` | Gepinnte OpenAI-Tokenliste ins exakt bestätigte `vocab.bin`-Format überführen. |
| `make-model-package.py`, `model-packages.json` | Ausschließlich bekannte abgenommene Modellbytes prüfen/kopieren und `tapstop-model.json` erstellen. |
| `stage-runtime.py`, `runtime-files.json` | Vorhandene validierte PoC-Runtimeobjekte und exakte SDK-Hinweise für eine lokale APK bereitstellen. |

Die beiden Staging-Werkzeuge lesen vorhandene Referenzen; sie entwickeln nicht in
den PoCs weiter. Sie schreiben nur in ein **neues** Zielverzeichnis. Ein bestehendes
Verzeichnis wird nicht überschrieben. Manifestdateien werden erst nach Prüfung
aller übertragenen Dateien geschrieben.

## 1. Exportumgebung und gepinnte Eingaben

Aus dem TapStop-Repository ausführen. `qualcomm-work/` ist ein lokaler,
gitignorierter Arbeitsbereich. Ausreichend Platz für Gewichte, ONNX External Data,
DLCs und Contexts vorhalten; mehrfach mehr als die circa 2,2 GB fertiger Modelle.
Keine System-Python-Installation ändern.

```sh
python3.12 -m venv qualcomm-work/venv-export
qualcomm-work/venv-export/bin/python -m pip install -r tools/qualcomm-whisper/export-requirements.txt
qualcomm-work/venv-export/bin/python tools/qualcomm-whisper/fetch-sources.py \
  --output qualcomm-work/aihub
```

Die öffentlichen Quellen sind auf QAI Hub Models v0.63.0, Commit
`671590e9e5c3121e3c1ef693444f0787638d6951` gepinnt. Das Skript lädt von den
in diesem Commit vorhandenen offiziellen GitHub-Pfaden; es verwendet keine
Cloud-Compile-API. Die BSD-3-Clause-Lizenz bleibt neben den Quellen erhalten.

Die offiziellen OpenAI-Gewichte aus
[openai/whisper-large-v3-turbo](https://huggingface.co/openai/whisper-large-v3-turbo/tree/41f01f3fe87f28c78e2fbf8b568835947dd65ed9)
lokal beziehen. Beispielsweise mit dem zum gepinnten `huggingface-hub` gehörenden
Python-Client:

```sh
qualcomm-work/venv-export/bin/python - <<'PY'
from huggingface_hub import snapshot_download
snapshot_download(
    'openai/whisper-large-v3-turbo',
    revision='41f01f3fe87f28c78e2fbf8b568835947dd65ed9',
    local_dir='qualcomm-work/openai-whisper',
    allow_patterns=['*.json', 'model.safetensors', 'README.md'],
)
PY
```

`model.safetensors`: 1.617.824.864 Bytes, SHA-256
`542566a422ae4f3fd23f1ba11add198fca01bbf82e66e6a2857b3f608b1eb9d1`.
Der Exporter prüft den Hash und die Modellkonfiguration einschließlich 32
Encoderlayer, 4 Decoderlayer, 128 Mel-Bins, 1.280 Dimensionen und 51.866 Tokens.

## 2. Export und numerischer Vergleich

```sh
qualcomm-work/venv-export/bin/python tools/qualcomm-whisper/export-onnx.py \
  --source qualcomm-work/aihub \
  --model-path qualcomm-work/openai-whisper \
  --output qualcomm-work/onnx

qualcomm-work/venv-export/bin/python tools/qualcomm-whisper/verify-onnx.py \
  --model-path qualcomm-work/openai-whisper \
  --onnx qualcomm-work/onnx/hf_whisper_decoder.onnx \
  --evidence qualcomm-work/onnx/independent-decoder-check.json
```

Der Export läuft auf CPU, mit statischen Cache-Schnittstellen und ONNX-Opset 18.
Die externe Gewichtsdatei gehört immer zu ihrer ONNX-Datei. Der Exporter verwendet
`dynamo=True`, `external_data=True`, `optimize=True`; der offizielle Optimizer ist
hier relevant: ein gültiges unäres `Max` im unoptimierten Decoder löste im
QAIRT-Konverter einen Fehler aus. Das optimierte Modell vermeidet diesen
Konverterfehler ohne SDK-Patch oder eigenen Ersatzoperator.

Die geprüften Pins `onnxscript=0.4.0`, `onnx-ir=0.1.7` und `protobuf=6.33.5`
gehören zusammen. Neuere ONNX-IR-/Protobuf-Versionen scheiterten im PoC bereits
an kleinen Export-/Attributtests. Der Exporter prüft alle IO-Namen, Shapes und
Datentypen. Der Encoder hat einen Input `[1,128,3000]` und acht Cross-Caches;
der Decoder 19 Inputs und neun Outputs. Self-Caches enthalten 199 Positionen,
das Attentionfenster 200 Positionen.

Im ursprünglichen erfolgreichen Build lagen maximale Decoder-Logitabweichungen
gegenüber Hugging Face bei 0,0000257492 nach Anpassung und 0,0000641346 nach
ONNX-Export, mit identischen argmax-Tokens. Die Skripte prüfen neu erzeugte
Modelle erneut; diese früheren Messwerte sind keine zugesicherte Obergrenze.

## 3. QAIRT-Konvertierung auf Linux x86_64

QAIRT **2.50.0.260828** selbst aus dem
[offiziellen Software Center](https://softwarecenter.qualcomm.com/catalog/item/Qualcomm_AI_Runtime_Community)
beziehen, Lizenz lesen und die dokumentierten Python-/Systemabhängigkeiten des
SDKs in einer isolierten Linux-Umgebung bereitstellen. Der Converter benötigt
passende native Python-3.10- oder -3.12-Bindings; der erfolgreiche Forschungsbuild
verwendete Python 3.12 in einer lokalen Linux-x86_64-VM. MacOS ist der
Exporthost, nicht der native QAIRT-Compilerhost.

Die folgenden Befehle setzen voraus, dass Repository und `qualcomm-work/` auf
Linux zugänglich sind. `QAIRT_SDK_DIR` auf das lokal entpackte Versionsverzeichnis
setzen, beispielsweise `/opt/qairt/2.50.0.260828`. Der Pfad ist ein Platzhalter,
keine automatische Installation.

```sh
python3 tools/qualcomm-whisper/compile-contexts.py \
  --sdk-root "$QAIRT_SDK_DIR" \
  --encoder qualcomm-work/onnx/hf_whisper_encoder.onnx \
  --decoder qualcomm-work/onnx/hf_whisper_decoder.onnx \
  --soc-model 97 --dsp-arch 81 \
  --output-dir qualcomm-work/sm8845-contexts
```

Für einen experimentellen SM8650-Neubuild sind die Targetparameter
`--soc-model 57 --dsp-arch 75`. Weitere Zahlen nicht aus Marketingnamen erraten;
QAIRT-Support, Stub/Skeleton und den tatsächlichen SoC unabhängig prüfen.

Der Compiler erzeugt `PerfSetting.conf` mit den tatsächlichen Graphnamen,
`soc_model`, `dsp_arch`, unsigned PD, `vtcm_mb=8`, Optimierung 3.0 sowie
Burst-Performanceprofil. Diese Einstellungen stammen aus dem funktionierenden
SM8845-Build. Er setzt SDK-Suchpfade nur für seine Kindprozesse.

Die wesentlichen Aufrufe sind:

```text
qairt-converter --float_bitwidth 16 --float_bias_bitwidth 16
  --config <explizite FP16-IO-Konfiguration> --onnx_skip_simplification
qnn-context-binary-generator --model libQnnModelDlc.so --dlc_path <DLC>
  --backend libQnnHtp.so --config_file <HTP-Konfiguration> --retain_tensor_name
qnn-context-binary-utility --context_binary <BIN> --json_file <Inspektion>
```

`input_ids` und `position_ids` bleiben ausdrücklich INT32. Für den Encoder wird
die Eingabe als `other` behandelt, für den Decoder die Cache-/Head-Achsen als
`NONTRIVIAL`. Der von QAIRT 2.50 akzeptierte ältere Parametername
`--source_model_input_layout` bleibt dafür bewusst erhalten.

Ein Fehlercode bricht den Build ab. Im Forschungsbuild trat nach erfolgreichem
Encoder-Speichern ein Wrapperabbruch mit `futex`/Exit 134 auf; dies wurde nicht
als Erfolg ignoriert. Eine separat gestartete offizielle Context-Inspektion
bestand mit Exit 0, danach Hash-/Interfaceprüfung und echte HTP-Ausführung.
Eine existierende BIN-Datei allein ist daher kein Erfolgskriterium.

## 4. Contexts unabhängig prüfen

Nach einer erfolgreich abgeschlossenen offiziellen Inspektion kann deren JSON
ohne SDK oder Gewichte nochmals geprüft werden:

```sh
python3 tools/qualcomm-whisper/compile-contexts.py \
  --inspect-json qualcomm-work/sm8845-contexts/encoder-context.json \
  --component encoder --soc-model 97 --dsp-arch 81
python3 tools/qualcomm-whisper/compile-contexts.py \
  --inspect-json qualcomm-work/sm8845-contexts/decoder-context.json \
  --component decoder --soc-model 97 --dsp-arch 81
```

Die Prüfung verlangt Backend 6, richtigen QAIRT-Build, genau einen korrekt
benannten Graphen, SoC 97/V81 und **jeden** IO-Namen/Shape/Datentyp. Sie ersetzt
keine Prüfung der gehashten BIN-Datei mit dem Qualcomm-Tool. Das erzeugte
`context-inspection.json` bindet die geprüften Daten an Größe und SHA-256 jeder
BIN. `hardwareValidated` bleibt ausdrücklich `false`.

## 5. Vokabular

Die [gepinnten OpenAI-Tokens](https://raw.githubusercontent.com/openai/whisper/839639a223b92ad61851baae9ad8a695ccb41ce5/whisper/assets/multilingual.tiktoken)
als `qualcomm-work/multilingual.tiktoken` beziehen und konvertieren:

```sh
python3 tools/qualcomm-whisper/make-vocab.py \
  --tiktoken qualcomm-work/multilingual.tiktoken \
  --output qualcomm-work/vocab.bin
```

Das Werkzeug verlangt den Quellhash
`b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126`
und den Ergebnis-Hash
`0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b`.
Das Ergebnis umfasst 357.313 Bytes und ist zwischen beiden validierten Targets
byteidentisch. Keine SDK-Vocab-Generatorquelle wird kopiert.

## 6. Hardwareabnahme vor neuem Targeteintrag

Die folgenden Schritte sind Freigabevoraussetzungen, keine automatisch vom
Compiler zugesicherten Eigenschaften:

1. Encoder und Decoder für das konkrete Target vollständig erzeugen.
2. Context-Metadaten, alle Tensorinterfaces und Modellhashes prüfen.
3. Direkter QNN-Test auf realer Hardware: Encoder mit bekanntem Audio, endliche
   korrekte Cacheausgaben; Decoder mit diesen Caches und gültigen Tokens,
   endliche Logits und Outputs. Ein Null-/Miniaturgraph reicht nicht.
4. VoiceAI-End-to-End-STT mit finalem gültigem Text und Rückgabecode 0 prüfen.
5. App-lokalen passenden Skeleton, FastRPC/CDSP Domain 3 und denselben Handle
   während **Encoder und Decoder** des aktuellen Laufs nachweisen. Ein geladenes
   ARM-`.so` oder erfolgreiches Init allein belegt keine Whisper-NPU-Ausführung.
6. Mindestens ein echtes Mikrofon-Diktat, wiederholte Sessions und die
   30-s-Grenze prüfen. Erst danach Targetdefinition/Hashpaket zur Review ergänzen.

TapStop unterstützt höchstens 30 Sekunden pro lokalem Diktat. Ein vollständiges
Audio wird einmal an VoiceAI übergeben; internes VAD oder mehrere interne
Encoderläufe bleiben Sache des SDKs. Das misslungene Long-Form-Chunking wird
nicht übernommen. Native `deInit → init` im selben Android-Prozess bleibt
unzuverlässig; die Runtime besitzt das Modell pro Prozess und arbeitet seriell.

## Bereits abgenommene Modelle lokal importierbar machen

`make-model-package.py` akzeptiert nur die drei fest gehashten Modelldateien der
jeweiligen Abnahme. Bei einem neu erzeugten Context mit anderem Hash schlägt
dieser Schritt bewusst fehl; keine automatische Aufnahme in die Freigabeliste.

```sh
python3 tools/qualcomm-whisper/make-model-package.py \
  --target sm8845-v81 --source "$VALIDATED_MODEL_DIR" \
  --output qualcomm-work/import-sm8845
```

Für S24 `--target sm8650-v75` und das validierte **VoiceAI-V75-Paket** verwenden.
Nicht dessen abweichenden alternativen `qnn_context_binary`-Encoder einsetzen.
Der neue Ordner enthält `encoder.bin`, `decoder.bin`, `vocab.bin` und das kleine
`tapstop-model.json`. In TapStop den Ordner über den Android-Dateiauswahldialog
importieren. Der App-Importer prüft Target, Runtime-Versionen, Größen und Hashes
nochmals vor Aktivierung.

## Runtime für lokale Entwicklungs-APKs

Die vorhandenen validierten PoCs bleiben ausschließlich lesbare Quellen.
Pfadvariablen auf deren lokale Verzeichnisse und die offiziellen SDK-Texte setzen:

```sh
python3 tools/qualcomm-whisper/stage-runtime.py \
  --s24-poc "$S24_POC_DIR" --magicpad-poc "$MAGICPAD_POC_DIR" \
  --voiceai-sdk "$VOICEAI_VERSION_DIR" \
  --qairt-license-dir "$QAIRT_LICENSE_DIR" \
  --output qualcomm-work/runtime
./gradlew -PqualcommRuntimeDir="$PWD/qualcomm-work/runtime" assembleDebug
```

`VOICEAI_VERSION_DIR` ist das Verzeichnis mit `whisper_sdk/` und
`Qualcomm AI Stack Proprietary License.pdf`; `QAIRT_LICENSE_DIR` enthält die
unveränderten `LICENSE.pdf`, `NOTICE.txt`, `QNN_NOTICE.txt` aus QAIRT 2.50.
Für Entwickler ohne diese Forschungskopien beschreibt `runtime-files.json` die
exakten Dateien, die aus den offiziellen SDKs im selben Layout bereitgestellt
werden müssen. Es gibt keinen versteckten SDK-Download oder Cloud-Fallback.

Native ARM64-Objekte liegen unter `jniLibs/arm64-v8a/`, die JAR unter `libs/`,
beide Skeletons unter `assets/qualcomm/`. Sechs Lizenz-/Notice-Dateien liegen
unter `assets/notices/`. `runtime-manifest.json` enthält relative Pfade und
Hashes, keine privaten Quellpfade. Modelle sind niemals Bestandteil dieses APKs.

`libQnnHtpPrepare.so` (85.026.120 Bytes) ist nicht in der Staging-Liste und wird
nicht in die APK übernommen. Erfolgreiche TapStop-SDK-Sitzungen mit dieser
reduzierten APK sind auf **beiden** Targets nachgewiesen: SM8650/V75 und
SM8845(P)/V81, jeweils mit bestätigter Encoder-/Decoder-Ausführung über HTP und
FastRPC. Für die geprüften fertigen Contexts ist die Compile-/Prepare-Komponente
zur Laufzeit entbehrlich. Dieser Laufzeitnachweis ersetzt keine vollständige
Mikrofon-/Produktabnahme; deren Ergebnisse und bekannte SDK-Grenzen stehen in
[DEVICE_TESTS.md](../../DEVICE_TESTS.md). Die Originaldatei bleibt im
SDK/Forschungsbestand erhalten.

## Prüfung der übertragenen Werkzeuge

```sh
python3 -m unittest discover -s tools/qualcomm-whisper -p 'test_*.py'
```

Die isolierten Tests lehnen falsches SoC bei gleichem HTP, falsche Runtime,
Backend/Graphen, fehlende/doppelte Inputs sowie falsche Shapes/Datentypen ab.
Zusätzlich wurde der allgemeine Tensorvertrag gegen alle vier finalen
Original-Contextinspektionen geprüft und das Vokabular mit identischem Hash
neu erzeugt. Runtime-Staging wurde gegen beide unveränderten PoCs praktisch
ausgeführt. Der vollständige Mehr-GB-Export/Compile wird durch die Übernahme
nicht als erneut ausgeführt behauptet; seine Hardwaregrundlage ist der
dokumentierte erfolgreiche Forschungsbuild.
