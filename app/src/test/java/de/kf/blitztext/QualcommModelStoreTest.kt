package de.kf.blitztext

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class QualcommModelStoreTest {
    private lateinit var root: File
    private lateinit var store: QualcommModelStore
    private val contents = linkedMapOf("encoder.bin" to "encoder", "decoder.bin" to "decoder", "vocab.bin" to "vocab")
    private val target = QualcommTargets.SM8845.copy(modelFiles = contents.map { (name, content) ->
        QualcommModelFile(name, content.toByteArray().size.toLong(), digest(content))
    })

    @Before fun setup() {
        root = Files.createTempDirectory("tapstop-model-test").toFile()
        store = QualcommModelStore(File(root, "models"))
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun source(
        manifest: JSONObject = QualcommModelStore.manifest(target),
        files: Map<String, String> = contents
    ): QualcommModelSource = QualcommModelSource { name ->
        (if (name == QualcommModelStore.MANIFEST_NAME) manifest.toString() else files[name])?.byteInputStream()
    }

    @Test fun missingDirectoryAndEachMissingRequiredFileCannotStart() {
        assertEquals(QualcommModelState.MISSING, store.inspect(target).state)
        assertTrue(runCatching { store.requireValid(target) }.isFailure)
        for (name in contents.keys + QualcommModelStore.MANIFEST_NAME) {
            val directory = store.importPackage(target, source()).directory
            File(directory, name).delete()
            assertEquals(QualcommModelState.MISSING, store.inspect(target, true).state)
            assertTrue(runCatching { store.requireValid(target) }.isFailure)
        }
    }

    @Test fun importedFilesAreFullyVerifiedAndQuickInspectionDoesNotClaimHashVerification() {
        val status = store.importPackage(target, source())
        assertEquals(QualcommModelState.READY, status.state)
        assertTrue(status.hashesVerified)
        assertEquals(status.directory, store.requireValid(target))
        assertFalse(store.inspect(target).hashesVerified)
        assertEquals(contents.keys + QualcommModelStore.MANIFEST_NAME, status.directory.list()!!.toSet())
    }

    @Test fun wrongTargetAndRuntimeMetadataAreRejectedBeforeAnyModelFileIsOpened() {
        for ((key, value) in listOf("target_id" to "sm8650-v75", "soc_model" to 87,
            "htp_version" to 75, "qairt_version" to "2.40", "voiceai_version" to "old", "precision" to "int8")) {
            val manifest = QualcommModelStore.manifest(target).put(key, value)
            var openedModel = false
            val bad = QualcommModelSource { name ->
                if (name == QualcommModelStore.MANIFEST_NAME) manifest.toString().byteInputStream()
                else { openedModel = true; error("Must validate target first") }
            }
            assertTrue("$key must be pinned", runCatching { store.importPackage(target, bad) }.isFailure)
            assertFalse(openedModel)
            assertEquals(QualcommModelState.MISSING, store.inspect(target).state)
        }
    }

    @Test fun editableManifestCannotBlessModifiedWeightsOrUnexpectedFilenames() {
        val edited = QualcommModelStore.manifest(target)
        edited.getJSONObject("files").getJSONObject("encoder.bin").put("sha256", digest("changed"))
        assertTrue(runCatching { store.importPackage(target, source(edited)) }.isFailure)
        val traversal = QualcommModelStore.manifest(target)
        traversal.getJSONObject("files").put("../secret", JSONObject())
        assertTrue(runCatching { store.importPackage(target, source(traversal)) }.isFailure)
    }

    @Test fun sameSizeHashMismatchIsRejectedAndNoStagingDataRemains() {
        assertTrue(runCatching {
            store.importPackage(target, source(files = contents + ("encoder.bin" to "changed")))
        }.isFailure)
        assertEquals(QualcommModelState.MISSING, store.inspect(target).state)
        assertTrue(store.modelsRoot.list().orEmpty().isEmpty())
    }

    @Test fun truncatedAndOversizedStreamsAreRejected() {
        for (invalid in listOf("x", "this-is-longer")) {
            assertTrue(runCatching {
                store.importPackage(target, source(files = contents + ("encoder.bin" to invalid)))
            }.isFailure)
            assertTrue(store.modelsRoot.list().orEmpty().isEmpty())
        }
    }

    @Test fun installedCorruptionFailsFullValidationAndRepairNeverReplacesAnActiveGeneration() {
        val original = store.importPackage(target, source()).directory
        File(original, "encoder.bin").writeText("changed")
        assertEquals(QualcommModelState.INVALID, store.inspect(target, true).state)
        assertTrue(runCatching { store.requireValid(target) }.isFailure)
        val repaired = store.importPackage(target, source()).directory
        assertNotEquals(original, repaired)
        assertEquals("changed", File(original, "encoder.bin").readText())
        assertEquals("encoder", File(repaired, "encoder.bin").readText())
        assertEquals(repaired, store.requireValid(target))
    }

    @Test fun failedImportKeepsPreviouslySelectedGenerationAndItsData() {
        val original = store.importPackage(target, source()).directory
        File(original, "vocab.bin").delete()
        assertTrue(runCatching {
            store.importPackage(target, source(files = contents - "decoder.bin"))
        }.isFailure)
        assertEquals(original, store.directoryFor(target))
        assertTrue(original.isDirectory)
        assertEquals("encoder", File(original, "encoder.bin").readText())
        assertFalse(store.modelsRoot.list().orEmpty().any { it.startsWith(".import-") })
    }

    @Test fun pointerCannotEscapeAppModelDirectory() {
        store.modelsRoot.mkdirs()
        File(store.modelsRoot, "${target.packageId}.current").writeText("../../outside")
        assertEquals(QualcommModelState.INVALID, store.inspect(target).state)
        assertTrue(runCatching { store.requireValid(target) }.isFailure)
    }

    private fun digest(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
