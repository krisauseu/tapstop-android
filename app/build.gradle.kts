import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional, locally licensed SDK bundle. The public source tree contains no SDK binaries.
val qualcommRuntimeDir = providers.gradleProperty("qualcommRuntimeDir").orNull?.let { file(it) }

android {
    namespace = "de.kf.blitztext"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.kf.blitztext"
        minSdk = 29
        targetSdk = 35
        versionCode = 9
        versionName = "0.9"
        testInstrumentationRunner = "de.kf.blitztext.StatsInstrumentation"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }
    qualcommRuntimeDir?.let { runtime ->
        sourceSets.getByName("main") {
            jniLibs.srcDir(runtime.resolve("jniLibs"))
            assets.srcDir(runtime.resolve("assets"))
        }
    }
}

dependencies {
    qualcommRuntimeDir?.let { implementation(fileTree(it.resolve("libs")) { include("*.jar") }) }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

qualcommRuntimeDir?.let { runtime ->
    val verifyRuntime = tasks.register("verifyQualcommRuntime") {
        doLast {
            @Suppress("UNCHECKED_CAST")
            val pins = JsonSlurper().parse(rootProject.file("tools/qualcomm-whisper/runtime-files.json")) as Map<String, Map<String, Any>>
            for ((name, pin) in pins) {
                val relative = when {
                    name.endsWith(".jar") -> "libs/$name"
                    name.contains("Skel") -> "assets/qualcomm/$name"
                    else -> "jniLibs/arm64-v8a/$name"
                }
                val binary = runtime.resolve(relative)
                check(binary.isFile && binary.length() == (pin.getValue("bytes") as Number).toLong()) { "Missing or invalid Qualcomm artifact: $relative" }
                val hash = MessageDigest.getInstance("SHA-256")
                binary.inputStream().use { input ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) { val n = input.read(buffer); if (n < 0) break; hash.update(buffer, 0, n) }
                }
                check(hash.digest().joinToString("") { "%02x".format(it) } == pin["sha256"]) { "Qualcomm artifact SHA-256 mismatch: $relative" }
            }
            val binaries = listOf("libs", "jniLibs", "assets/qualcomm").flatMap { folder ->
                runtime.resolve(folder).walkTopDown().filter { it.isFile && it.extension in setOf("jar", "so") }.toList()
            }
            check(binaries.map { it.name }.toSet() == pins.keys) { "Unexpected Qualcomm binaries in runtime bundle" }
            for (notice in listOf("VoiceAI-AI-Stack-License.pdf", "VoiceAI-LICENSE.txt", "VoiceAI-NOTICE.txt", "QAIRT-LICENSE.pdf", "QAIRT-NOTICE.txt", "QNN-NOTICE.txt")) {
                check(runtime.resolve("assets/notices/$notice").isFile) { "Missing Qualcomm license/notice: $notice" }
            }
        }
    }
    tasks.named("preBuild") { dependsOn(verifyRuntime) }
}

// Keep the existing debug signing identity for compatible updates.
tasks.register<Copy>("packageTapStop") {
    dependsOn("assembleDebug")
    from(layout.buildDirectory.file("outputs/apk/debug/app-debug.apk"))
    into(layout.buildDirectory.dir("outputs/apk/tapstop"))
    rename { "TapStop-Android-${android.defaultConfig.versionName}.apk" }
}
