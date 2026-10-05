package de.kf.blitztext

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID

enum class QualcommModelState { READY, MISSING, INVALID }
data class QualcommModelStatus(
    val state: QualcommModelState,
    val message: String,
    val directory: File,
    val hashesVerified: Boolean = false
) {
    val ready get() = state == QualcommModelState.READY
}

/** Opens exactly the expected package files, never arbitrary manifest paths or remote URLs. */
fun interface QualcommModelSource { fun open(name: String): InputStream? }

/**
 * Models live outside the APK. A manifest is descriptive; the compiled target registry is authoritative.
 * Imports create immutable generations and atomically switch a small pointer. A runtime already holding
 * an older generation can finish safely; its files are never replaced or deleted by this store.
 * All import/hash operations must run on a worker, including requireValid() before native initialization.
 */
class QualcommModelStore internal constructor(
    val modelsRoot: File,
    private val resolver: ContentResolver? = null
) {
    constructor(context: Context) : this(
        File(checkNotNull(context.applicationContext.getExternalFilesDir(null)) {
            "App-Modellspeicher ist nicht verfügbar."
        }, "qualcomm-models"), context.applicationContext.contentResolver
    )

    fun directoryFor(target: QualcommTarget): File {
        val pointer = File(modelsRoot, "${target.packageId}.current")
        val name = if (pointer.exists()) {
            check(pointer.length() <= 256) { "Ungültiger Modellpfad." }
            pointer.readText().trim().also {
                check(it.startsWith("${baseName(target)}-") && it.matches(Regex("[a-z0-9-]+"))) {
                    "Ungültiger Modellpfad."
                }
            }
        } else baseName(target)
        return File(modelsRoot, name).also {
            check(it.canonicalFile.parentFile == modelsRoot.canonicalFile) { "Ungültiger Modellpfad." }
        }
    }

    fun inspect(target: QualcommTarget, verifyHashes: Boolean = false): QualcommModelStatus {
        val directory = try { directoryFor(target) } catch (e: Exception) {
            return QualcommModelStatus(QualcommModelState.INVALID,
                e.message ?: "Ungültiger Modellpfad.", modelsRoot)
        }
        return inspectDirectory(target, directory, verifyHashes)
    }

    fun requireValid(target: QualcommTarget): File {
        val status = inspect(target, verifyHashes = true)
        check(status.ready && status.hashesVerified) { status.message }
        return status.directory
    }

    fun importDirectory(target: QualcommTarget, treeUri: Uri): QualcommModelStatus =
        importPackage(target, SafModelSource(checkNotNull(resolver), treeUri))

    fun importPackage(target: QualcommTarget, source: QualcommModelSource): QualcommModelStatus = synchronized(importLock) {
        check(modelsRoot.isDirectory || modelsRoot.mkdirs()) { "Modellspeicher konnte nicht angelegt werden." }
        val manifestText = source.open(MANIFEST_NAME)?.use { readBounded(it, MAX_MANIFEST_BYTES) }
            ?: error("$MANIFEST_NAME fehlt im gewählten Modellordner.")
        validateManifest(target, JSONObject(manifestText))

        val existing = inspect(target, verifyHashes = true)
        if (existing.ready) return@synchronized existing
        val bytesRequired = target.modelFiles.sumOf { it.sizeBytes } + 10 * 1024 * 1024
        check(modelsRoot.usableSpace >= bytesRequired) { "Nicht genug freier Speicher für das lokale Modell." }

        val generation = "${baseName(target)}-${UUID.randomUUID()}"
        val staging = File(modelsRoot, ".import-${UUID.randomUUID()}")
        val installed = File(modelsRoot, generation)
        check(staging.mkdir()) { "Modellimport konnte nicht gestartet werden." }
        var committed = false
        try {
            File(staging, MANIFEST_NAME).writeText(manifestText)
            for (expected in target.modelFiles) {
                val input = source.open(expected.name) ?: error("${expected.name} fehlt im gewählten Modellordner.")
                input.use { copyVerified(it, File(staging, expected.name), expected) }
            }
            val checked = inspectDirectory(target, staging, verifyHashes = false)
            check(checked.ready) { checked.message }
            check(staging.renameTo(installed)) { "Modellimport konnte nicht abgeschlossen werden." }
            val pointerTemp = File(modelsRoot, ".current-${UUID.randomUUID()}")
            try {
                pointerTemp.outputStream().use { output ->
                    output.write(generation.toByteArray(Charsets.UTF_8)); output.fd.sync()
                }
                Files.move(pointerTemp.toPath(), File(modelsRoot, "${target.packageId}.current").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
            } finally { pointerTemp.delete() }
            QualcommModelStatus(QualcommModelState.READY, "Modell geprüft und bereit.", installed, hashesVerified = true)
        } finally {
            staging.deleteRecursively()
            if (!committed) installed.deleteRecursively()
        }
    }

    private fun inspectDirectory(target: QualcommTarget, directory: File, verifyHashes: Boolean): QualcommModelStatus {
        fun invalid(message: String) = QualcommModelStatus(QualcommModelState.INVALID, message, directory)
        if (!directory.isDirectory) return QualcommModelStatus(QualcommModelState.MISSING,
            "Lokales Modell fehlt. Bitte das passende Modellpaket importieren.", directory)
        val required = listOf(MANIFEST_NAME) + target.modelFiles.map { it.name }
        val missing = required.filter { !File(directory, it).isFile }
        if (missing.isNotEmpty()) return QualcommModelStatus(QualcommModelState.MISSING,
            "Modell unvollständig: ${missing.joinToString()} fehlt.", directory)
        return try {
            check(required.all { File(directory, it).canonicalFile.parentFile == directory.canonicalFile }) {
                "Modellpaket enthält einen ungültigen Dateipfad."
            }
            val manifest = File(directory, MANIFEST_NAME)
            check(manifest.length() <= MAX_MANIFEST_BYTES) { "Modellmetadaten sind zu groß." }
            validateManifest(target, JSONObject(manifest.readText()))
            for (expected in target.modelFiles) {
                val file = File(directory, expected.name)
                check(file.length() == expected.sizeBytes) { "Ungültige Dateigröße: ${expected.name}." }
                if (verifyHashes) check(sha256(file) == expected.sha256) { "Hashfehler: ${expected.name}. Modell wird nicht gestartet." }
            }
            QualcommModelStatus(QualcommModelState.READY,
                if (verifyHashes) "Modell geprüft und bereit." else "Modell vorhanden · Hashprüfung bei Vorbereitung.",
                directory, hashesVerified = verifyHashes)
        } catch (e: Exception) { invalid(e.message ?: "Ungültiges Modellpaket.") }
    }

    companion object {
        const val MANIFEST_NAME = "tapstop-model.json"
        private const val MAX_MANIFEST_BYTES = 65_536L
        private val importLock = Any()
        private fun baseName(target: QualcommTarget) = "${target.packageId}-${target.packageVersion}"

        /** Template for developer provisioning and later official downloads; no download origin is assumed. */
        fun manifest(target: QualcommTarget): JSONObject = JSONObject().apply {
            put("schema_version", 1)
            put("target_id", target.id); put("package_id", target.packageId); put("package_version", target.packageVersion)
            put("model_id", target.modelId); put("precision", "fp16")
            put("soc_model", target.socModel); put("htp_version", target.htpVersion)
            put("voiceai_version", target.voiceAiVersion); put("qairt_version", target.qairtVersion)
            put("files", JSONObject().apply {
                target.modelFiles.forEach { put(it.name, JSONObject().put("bytes", it.sizeBytes).put("sha256", it.sha256)) }
            })
        }

        private fun validateManifest(target: QualcommTarget, actual: JSONObject) {
            val expected = manifest(target)
            for (key in listOf("schema_version", "target_id", "package_id", "package_version", "model_id", "precision",
                "soc_model", "htp_version", "voiceai_version", "qairt_version")) {
                check(actual.has(key) && actual.get(key).toString() == expected.get(key).toString()) {
                    "Inkompatibles Modellpaket: $key passt nicht zu ${target.id}."
                }
            }
            val files = actual.getJSONObject("files")
            check(files.keys().asSequence().toSet() == target.modelFiles.map { it.name }.toSet()) {
                "Ungültiger Modell-Dateibestand."
            }
            for (file in target.modelFiles) {
                val entry = files.getJSONObject(file.name)
                check(entry.getLong("bytes") == file.sizeBytes && entry.getString("sha256") == file.sha256) {
                    "Inkompatible Modellprüfsumme: ${file.name}."
                }
            }
        }

        private fun readBounded(input: InputStream, maxBytes: Long): String {
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes) { "Modellmetadaten sind zu groß." }
                bytes.write(buffer, 0, count)
            }
            return bytes.toString("UTF-8")
        }

        private fun copyVerified(input: InputStream, destination: File, expected: QualcommModelFile) {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1024 * 1024)
            var total = 0L
            destination.outputStream().use { output ->
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Modellimport abgebrochen.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= expected.sizeBytes) { "Ungültige Dateigröße: ${expected.name}." }
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            check(total == expected.sizeBytes) { "Unvollständige Modelldatei: ${expected.name}." }
            check(hex(digest.digest()) == expected.sha256) { "Hashfehler: ${expected.name}. Modell wurde nicht importiert." }
        }

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException("Modellprüfung abgebrochen.")
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return hex(digest.digest())
        }
        private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}

/** SAF grants access only to a user-selected directory; no filesystem or network permission is added. */
private class SafModelSource(private val resolver: ContentResolver, treeUri: Uri) : QualcommModelSource {
    private val documents: Map<String, Uri>
    init {
        require(treeUri.scheme == ContentResolver.SCHEME_CONTENT && DocumentsContract.isTreeUri(treeUri)) {
            "Bitte einen Modellordner auswählen."
        }
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
        val allowed = setOf(QualcommModelStore.MANIFEST_NAME, "encoder.bin", "decoder.bin", "vocab.bin")
        val entries = mutableMapOf<String, Uri>()
        resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)
            ?.use { cursor ->
                while (cursor.moveToNext()) {
                    val name = cursor.getString(1)
                    if (name !in allowed) continue
                    check(cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR && name !in entries) {
                        "Mehrdeutiger Modell-Dateibestand: $name."
                    }
                    entries[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            } ?: error("Modellordner konnte nicht gelesen werden.")
        documents = entries
    }
    override fun open(name: String): InputStream? = documents[name]?.let { resolver.openInputStream(it) }
}
