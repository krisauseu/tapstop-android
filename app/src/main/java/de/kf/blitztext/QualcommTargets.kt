package de.kf.blitztext

import android.os.Build
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class QualcommCompatibilityState {
    SUPPORTED_VALIDATED, SUPPORTED_SOC_UNVALIDATED_DEVICE, UNSUPPORTED,
    RUNTIME_UNAVAILABLE, MODEL_MISSING, MODEL_INVALID
}

data class QualcommModelFile(val name: String, val sizeBytes: Long, val sha256: String)
data class QualcommValidatedDevice(val manufacturer: String, val model: String)

/** Only targets backed by both inspected contexts and successful physical device tests belong here. */
data class QualcommTarget(
    val id: String,
    val socAliases: Set<String>,
    val marketingName: String,
    val htpVersion: Int,
    val socModel: Int,
    val packageId: String,
    val packageVersion: String,
    val modelFiles: List<QualcommModelFile>,
    val validatedDevices: Set<QualcommValidatedDevice>,
    val voiceAiVersion: String = "2.7.1.0",
    val qairtVersion: String = "2.50.0.260828"
) {
    val stubName get() = "libQnnHtpV${htpVersion}Stub.so"
    val skeletonName get() = "libQnnHtpV${htpVersion}Skel.so"
    val modelId get() = "whisper-large-v3-turbo"
}

data class QualcommHardware(
    val socModel: String,
    val socManufacturer: String,
    val manufacturer: String,
    val model: String,
    val abis: List<String>,
    val fastRpcAvailable: Boolean
)

data class QualcommCompatibility(
    val state: QualcommCompatibilityState,
    val target: QualcommTarget?,
    val message: String
) {
    val hardwareSupported get() = target != null && state != QualcommCompatibilityState.UNSUPPORTED
    val selectable get() = state == QualcommCompatibilityState.SUPPORTED_VALIDATED ||
        state == QualcommCompatibilityState.SUPPORTED_SOC_UNVALIDATED_DEVICE
}

object QualcommTargets {
    private val vocab = QualcommModelFile("vocab.bin", 357_313,
        "0ba87984671b92e03b56b84ce9b217020663f6a269b5a9800901391430b79c4b")

    val SM8650 = QualcommTarget(
        id = "sm8650-v75", socAliases = setOf("SM8650"), marketingName = "Snapdragon 8 Gen 3",
        htpVersion = 75, socModel = 57, packageId = "whisper-large-v3-turbo-sm8650-fp16",
        packageVersion = "1", modelFiles = listOf(
            QualcommModelFile("encoder.bin", 1_752_566_520,
                "3cbdc87dd400d7efd4f3a093dc3144fb5262e7b4331414581e2fe7c0ed6b12cb"),
            QualcommModelFile("decoder.bin", 452_486_656,
                "092bdc00f23c62b5863fa46a8dd1b683d9948eac357da5af1a26903a36c3362a"), vocab
        ), validatedDevices = setOf(QualcommValidatedDevice("samsung", "SM-S928B"))
    )
    val SM8845 = QualcommTarget(
        id = "sm8845-v81", socAliases = setOf("SM8845", "SM8845P"), marketingName = "Snapdragon 8 Gen 5",
        htpVersion = 81, socModel = 97, packageId = "whisper-large-v3-turbo-sm8845-fp16",
        packageVersion = "1", modelFiles = listOf(
            QualcommModelFile("encoder.bin", 1_752_666_608,
                "0a592c697de8b8dfe63cf2681155241807cb2c0b49e53d4dc1aea365a339a338"),
            QualcommModelFile("decoder.bin", 452_449_800,
                "dcdc7d2cf7168d090d589b1ae5da0e06ddd876c76a72a9375772f9de9b81e862"), vocab
        ), validatedDevices = setOf(QualcommValidatedDevice("HONOR", "YLE-W09"))
    )
    val all = listOf(SM8650, SM8845)

    fun detect(hardware: QualcommHardware): QualcommCompatibility {
        val soc = hardware.socModel.trim().uppercase(Locale.ROOT)
        val target = all.singleOrNull { soc in it.socAliases }
            ?: return QualcommCompatibility(QualcommCompatibilityState.UNSUPPORTED, null,
                "Für dieses Gerät ist derzeit kein lokales Qualcomm-Modell verfügbar.")
        val vendor = hardware.socManufacturer.trim().uppercase(Locale.ROOT)
        if (vendor != "QTI" && vendor != "QUALCOMM" && !vendor.startsWith("QUALCOMM TECHNOLOGIES")) {
            return QualcommCompatibility(QualcommCompatibilityState.UNSUPPORTED, null,
                "Qualcomm-Hardware konnte nicht bestätigt werden.")
        }
        if ("arm64-v8a" !in hardware.abis || !hardware.fastRpcAvailable) {
            return QualcommCompatibility(QualcommCompatibilityState.RUNTIME_UNAVAILABLE, target,
                "Die benötigte lokale ARM64-/FastRPC-Laufzeit ist nicht verfügbar.")
        }
        val validated = target.validatedDevices.any {
            it.manufacturer.equals(hardware.manufacturer.trim(), ignoreCase = true) &&
                it.model.equals(hardware.model.trim(), ignoreCase = true)
        }
        return QualcommCompatibility(
            if (validated) QualcommCompatibilityState.SUPPORTED_VALIDATED else QualcommCompatibilityState.SUPPORTED_SOC_UNVALIDATED_DEVICE,
            target, if (validated) "${target.marketingName} erkannt · kompatibel"
            else "${target.marketingName} erkannt · dieses Gerätemodell ist noch nicht getestet"
        )
    }
}

/** Read-only properties; never reads or retains a device serial number. Call from a worker. */
object AndroidQualcommHardware {
    fun read(): QualcommHardware = QualcommHardware(
        socModel = property("ro.soc.model").ifBlank { if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "" },
        socManufacturer = property("ro.soc.manufacturer").ifBlank { if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "" },
        manufacturer = Build.MANUFACTURER, model = Build.MODEL,
        abis = Build.SUPPORTED_ABIS.toList(),
        // The definitive availability check is the runtime's load through Android's native namespace.
        fastRpcAvailable = listOf("/vendor/lib64/libcdsprpc.so", "/system/vendor/lib64/libcdsprpc.so")
            .any { File(it).isFile }
    )

    private fun property(name: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", name).redirectErrorStream(true).start()
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS) || process.exitValue() != 0) ""
            else process.inputStream.bufferedReader().use { it.readLine().orEmpty().trim() }
        } finally { process.destroy() }
    }.getOrDefault("")
}
