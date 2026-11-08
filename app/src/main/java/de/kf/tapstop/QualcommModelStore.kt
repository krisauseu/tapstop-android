package de.kf.tapstop

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
    val message: UiMessage,
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
            throw UiFailure(UiMessage(R.string.model_storage_unavailable))
        }, "qualcomm-models"), context.applicationContext.contentResolver
    )

    fun directoryFor(target: QualcommTarget): File {
        val pointer = File(modelsRoot, "${target.packageId}.current")
        val name = if (pointer.exists()) {
            checkUi(pointer.length() <= 256, R.string.model_path_invalid)
            pointer.readText().trim().also {
                checkUi(it.startsWith("${baseName(target)}-") && it.matches(Regex("[a-z0-9-]+")),
                    R.string.model_path_invalid)
            }
        } else baseName(target)
        return File(modelsRoot, name).also {
            checkUi(it.canonicalFile.parentFile == modelsRoot.canonicalFile, R.string.model_path_invalid)
        }
    }

    fun inspect(target: QualcommTarget, verifyHashes: Boolean = false): QualcommModelStatus {
        val directory = try { directoryFor(target) } catch (e: Exception) {
            return QualcommModelStatus(QualcommModelState.INVALID,
                e.uiMessage(R.string.model_path_invalid), modelsRoot)
        }
        return inspectDirectory(target, directory, verifyHashes)
    }

    fun requireValid(target: QualcommTarget): File {
        val status = inspect(target, verifyHashes = true)
        if (!status.ready || !status.hashesVerified) throw UiFailure(status.message)
        return status.directory
    }

    fun importDirectory(target: QualcommTarget, treeUri: Uri): QualcommModelStatus =
        importPackage(target, SafModelSource(checkNotNull(resolver), treeUri))

    fun importPackage(target: QualcommTarget, source: QualcommModelSource): QualcommModelStatus = synchronized(importLock) {
        checkUi(modelsRoot.isDirectory || modelsRoot.mkdirs(), R.string.model_storage_create_failed)
        val manifestText = source.open(MANIFEST_NAME)?.use { readBounded(it, MAX_MANIFEST_BYTES) }
            ?: throw UiFailure(UiMessage(R.string.model_import_file_missing, MANIFEST_NAME))
        validateManifest(target, JSONObject(manifestText))

        val existing = inspect(target, verifyHashes = true)
        if (existing.ready) return@synchronized existing
        val bytesRequired = target.modelFiles.sumOf { it.sizeBytes } + 10 * 1024 * 1024
        checkUi(modelsRoot.usableSpace >= bytesRequired, R.string.model_storage_full)

        val generation = "${baseName(target)}-${UUID.randomUUID()}"
        val staging = File(modelsRoot, ".import-${UUID.randomUUID()}")
        val installed = File(modelsRoot, generation)
        checkUi(staging.mkdir(), R.string.model_import_start_failed)
        var committed = false
        try {
            File(staging, MANIFEST_NAME).writeText(manifestText)
            for (expected in target.modelFiles) {
                val input = source.open(expected.name) ?: throw UiFailure(UiMessage(R.string.model_import_file_missing, expected.name))
                input.use { copyVerified(it, File(staging, expected.name), expected) }
            }
            val checked = inspectDirectory(target, staging, verifyHashes = false)
            if (!checked.ready) throw UiFailure(checked.message)
            checkUi(staging.renameTo(installed), R.string.model_import_finish_failed)
            val pointerTemp = File(modelsRoot, ".current-${UUID.randomUUID()}")
            try {
                pointerTemp.outputStream().use { output ->
                    output.write(generation.toByteArray(Charsets.UTF_8)); output.fd.sync()
                }
                Files.move(pointerTemp.toPath(), File(modelsRoot, "${target.packageId}.current").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                committed = true
            } finally { pointerTemp.delete() }
            QualcommModelStatus(QualcommModelState.READY, UiMessage(R.string.model_verified), installed, hashesVerified = true)
        } finally {
            staging.deleteRecursively()
            if (!committed) installed.deleteRecursively()
        }
    }

    private fun inspectDirectory(target: QualcommTarget, directory: File, verifyHashes: Boolean): QualcommModelStatus {
        fun invalid(message: UiMessage) = QualcommModelStatus(QualcommModelState.INVALID, message, directory)
        if (!directory.isDirectory) return QualcommModelStatus(QualcommModelState.MISSING,
            UiMessage(R.string.model_missing), directory)
        val required = listOf(MANIFEST_NAME) + target.modelFiles.map { it.name }
        val missing = required.filter { !File(directory, it).isFile }
        if (missing.isNotEmpty()) return QualcommModelStatus(QualcommModelState.MISSING,
            UiMessage(R.string.model_incomplete, missing.joinToString()), directory)
        return try {
            checkUi(required.all { File(directory, it).canonicalFile.parentFile == directory.canonicalFile },
                R.string.model_file_path_invalid)
            val manifest = File(directory, MANIFEST_NAME)
            checkUi(manifest.length() <= MAX_MANIFEST_BYTES, R.string.model_metadata_too_large)
            validateManifest(target, JSONObject(manifest.readText()))
            for (expected in target.modelFiles) {
                val file = File(directory, expected.name)
                checkUi(file.length() == expected.sizeBytes, R.string.model_file_size_invalid, expected.name)
                if (verifyHashes) checkUi(sha256(file) == expected.sha256, R.string.model_file_hash_invalid, expected.name)
            }
            QualcommModelStatus(QualcommModelState.READY,
                if (verifyHashes) UiMessage(R.string.model_verified) else UiMessage(R.string.model_present),
                directory, hashesVerified = verifyHashes)
        } catch (e: Exception) { invalid(e.uiMessage(R.string.model_invalid)) }
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
                checkUi(actual.has(key) && actual.get(key).toString() == expected.get(key).toString(),
                    R.string.model_metadata_incompatible, key, target.id)
            }
            val files = actual.getJSONObject("files")
            checkUi(files.keys().asSequence().toSet() == target.modelFiles.map { it.name }.toSet(),
                R.string.model_file_list_invalid)
            for (file in target.modelFiles) {
                val entry = files.getJSONObject(file.name)
                checkUi(entry.getLong("bytes") == file.sizeBytes && entry.getString("sha256") == file.sha256,
                    R.string.model_file_hash_invalid, file.name)
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
                checkUi(total <= maxBytes, R.string.model_metadata_too_large)
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
                    checkUi(total <= expected.sizeBytes, R.string.model_file_size_invalid, expected.name)
                    digest.update(buffer, 0, count); output.write(buffer, 0, count)
                }
                output.fd.sync()
            }
            checkUi(total == expected.sizeBytes, R.string.model_file_incomplete, expected.name)
            checkUi(hex(digest.digest()) == expected.sha256, R.string.model_file_hash_invalid, expected.name)
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
            throw UiFailure(UiMessage(R.string.model_select_folder))
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
                    checkUi(cursor.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR && name !in entries,
                        R.string.model_file_ambiguous, name)
                    entries[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(0))
                }
            } ?: throw UiFailure(UiMessage(R.string.model_folder_unreadable))
        documents = entries
    }
    override fun open(name: String): InputStream? = documents[name]?.let { resolver.openInputStream(it) }
}
