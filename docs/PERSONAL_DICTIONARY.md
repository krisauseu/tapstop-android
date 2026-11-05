# Persönliches Wörterbuch

## Speicherung und Requests

Das Wörterbuch startet leer und liegt als `personal_dictionary` in den bestehenden
lokalen `settings`-Preferences. Keine Migration, Datenbank oder Synchronisation.
Die bestehenden Backup-Ausschlüsse gelten auch für diesen Schlüssel.
Leerzeilen werden ignoriert, Rand-Whitespace entfernt, Reihenfolge und innere
Leerzeichen bleiben erhalten. Maximal 200 vollständige Einträge / 8192 UTF-8-Bytes
(einschließlich Zeilentrenner). Bei Überschreitung wird nur der vollständige
Listenanfang gespeichert; die UI meldet dies.

Groq Whisper Large V3 und Large V3 Turbo sowie OpenAI whisper-1 bekommen das
optionale Multipart-Feld `prompt`. Es beschreibt nur mögliche Eigennamen und
Fachbegriffe. Beide Provider dokumentieren 224 Tokens:

- https://console.groq.com/docs/speech-to-text
- https://developers.openai.com/api/docs/guides/speech-to-text

`PersonalDictionary.sttPrompt` kapselt die Begrenzung: maximal 224 UTF-8-Bytes für
den gesamten Prompt einschließlich Kontext und Satzzeichen. Whisper verwendet
Byte-Level-BPE (`https://github.com/openai/whisper/blob/main/whisper/tokenizer.py`):
Ein Byte ist eine konservative obere Schranke für ein gewöhnliches Token.
Dies ist bewusst kleiner als die meist nutzbare Tokenkapazität und keine
Zeichen-/Token-Schätzung. Beim ersten nicht passenden Eintrag wird beendet,
spätere kürzere Einträge werden nicht vorgezogen. Auch ein zu langer erster
Eintrag erzeugt kein Prompt-Feld. Das Wörterbuch wird niemals mitten im Wort
abgeschnitten. Eine exakt ausgelastete Tokenkapazität benötigt künftig einen
passenden Tokenizer.

Die tatsächliche öffentliche API der lokalen VoiceAI-2.7.1.0-JAR wurde mit javap
geprüft. Sie bietet keinen Prompt-/Wörterbuch-Setter. Der verwendete
`start(FileInputStream)`-Pfad bekommt daher keinen künstlichen Kontext.

Plus, Chat und Formal behalten ihren originalen System-/Modus-Prompt und alle
Modell-/Temperaturparameter. Nur bei nichtleerem Wörterbuch folgen eine feste
System-Erklärung der Datenrolle und eine separate user-Message mit einem
JSON-Array von Schreibweisen. Die letzte user-Message bleibt das unveränderte
Diktat. Wörterbucheinträge werden nie in System-Instruktionen interpoliert.
JSON-Escaping verhindert strukturelle Rollen-/Message-Injektion; die feste
Instruktion untersagt das Ausführen der Daten und Hinzufügen ungesprochener
Begriffe. Sprachmodelle bieten trotzdem keine absolute Garantie gegen semantische
Prompt-Injektion oder falsche Ersetzungen. Leere Wörterbücher erzeugen die
bisherigen Requests. Roh sendet weiterhin keinen Rewrite-Request.

Wörterbuchinhalte werden nicht an DictationTrace oder UsageStats weitergereicht.
Cloud-STT und Cloud-Rewrite erhalten Kontext beim jeweiligen Request; die UI
weist darauf hin. Lokale STT mit Cloud-Rewrite nutzt das Wörterbuch nur dort.

Gemini-STT verwendet das Wörterbuch derzeit nicht. Bei Plus, Chat und Formal erhält der gewählte Rewrite-Provider den Kontext.
