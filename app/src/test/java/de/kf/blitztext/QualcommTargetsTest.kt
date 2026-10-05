package de.kf.blitztext

import org.junit.Assert.*
import org.junit.Test

class QualcommTargetsTest {
    private fun hardware(soc: String, manufacturer: String = "HONOR", model: String = "YLE-W09") =
        QualcommHardware(soc, "QTI", manufacturer, model, listOf("arm64-v8a"), true)

    @Test fun sm8650SelectsOnlyTheValidatedVoiceAiV75Package() {
        val detected = QualcommTargets.detect(hardware("SM8650", "samsung", "SM-S928B"))
        assertEquals(QualcommCompatibilityState.SUPPORTED_VALIDATED, detected.state)
        assertTrue(detected.selectable)
        assertEquals(57, detected.target!!.socModel)
        assertEquals(75, detected.target.htpVersion)
        assertEquals("3cbdc87dd400d7efd4f3a093dc3144fb5262e7b4331414581e2fe7c0ed6b12cb",
            detected.target.modelFiles.single { it.name == "encoder.bin" }.sha256)
    }

    @Test fun sm8845AndDocumentedSuffixSelectTheSameSoc97V81Package() {
        for (soc in listOf("SM8845", "SM8845P", " sm8845p ")) {
            val result = QualcommTargets.detect(hardware(soc))
            assertEquals(QualcommCompatibilityState.SUPPORTED_VALIDATED, result.state)
            assertEquals(97, result.target!!.socModel)
            assertEquals(81, result.target.htpVersion)
            assertSame(QualcommTargets.SM8845, result.target)
        }
    }

    @Test fun sameSocInAnotherDeviceIsSelectableButNeverClaimedValidated() {
        val detected = QualcommTargets.detect(hardware("SM8650", "OnePlus", "other"))
        assertEquals(QualcommCompatibilityState.SUPPORTED_SOC_UNVALIDATED_DEVICE, detected.state)
        assertTrue(detected.selectable)
        assertTrue(detected.message.contains("noch nicht getestet"))
    }

    @Test fun deviceMarketingNameDoesNotOverrideSocOrIntroduceUndocumentedAliases() {
        for (soc in listOf("", "SM8850", "SM8845-AB", "SM8650P", "Snapdragon 8 Gen 3", "unknown")) {
            val detected = QualcommTargets.detect(hardware(soc, "samsung", "SM-S928B"))
            assertEquals(QualcommCompatibilityState.UNSUPPORTED, detected.state)
            assertFalse(detected.selectable)
            assertNull(detected.target)
        }
    }

    @Test fun qualcommIdentityArm64AndFastRpcAreRequired() {
        val base = hardware("SM8845P")
        assertEquals(QualcommCompatibilityState.UNSUPPORTED,
            QualcommTargets.detect(base.copy(socManufacturer = "other")).state)
        assertEquals(QualcommCompatibilityState.RUNTIME_UNAVAILABLE,
            QualcommTargets.detect(base.copy(abis = listOf("armeabi-v7a"))).state)
        val unavailable = QualcommTargets.detect(base.copy(fastRpcAvailable = false))
        assertEquals(QualcommCompatibilityState.RUNTIME_UNAVAILABLE, unavailable.state)
        assertFalse(unavailable.selectable)
        assertTrue(QualcommTargets.detect(base.copy(socManufacturer = "Qualcomm Technologies, Inc")).selectable)
    }
}
