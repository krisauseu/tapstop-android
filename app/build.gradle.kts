import groovy.json.JsonSlurper
import java.security.MessageDigest
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional, locally licensed SDK bundle. The public source tree contains no SDK binaries.
val qualcommRuntimeDir = providers.gradleProperty("qualcommRuntimeDir").orNull?.let { file(it) }
val releaseSigning = providers.environmentVariable("TAPSTOP_SIGNING_PROPERTIES").orNull?.let { path ->
    val propertiesFile = rootProject.file(path)
    Properties().apply {
        propertiesFile.inputStream().use { load(it) }
        for (key in listOf("storeFile", "storePassword", "keyAlias", "keyPassword")) {
            check(!getProperty(key).isNullOrBlank()) { "Missing release signing setting: $key" }
        }
        check(File(getProperty("storeFile")).isAbsolute) { "Release storeFile must be absolute" }
    }
}

fun sha256(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(1024 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            hash.update(buffer, 0, count)
        }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}

android {
    namespace = "de.kf.tapstop"
    compileSdk = 36

    defaultConfig {
        applicationId = "de.kf.tapstop"
        minSdk = 29
        targetSdk = 35
        versionCode = 12
        versionName = "0.12"
        testInstrumentationRunner = "de.kf.tapstop.StatsInstrumentation"
        buildConfigField("boolean", "QUALCOMM_RUNTIME_INCLUDED", (qualcommRuntimeDir != null).toString())
        buildConfigField("boolean", "LOCAL_DIAGNOSTICS", "true")
    }

    releaseSigning?.let { properties ->
        signingConfigs.create("release") {
            storeFile = File(properties.getProperty("storeFile"))
            storePassword = properties.getProperty("storePassword")
            keyAlias = properties.getProperty("keyAlias")
            keyPassword = properties.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    if (providers.gradleProperty("releaseDeviceTests").orNull == "true") {
        check(releaseSigning != null) { "Release device tests require TAPSTOP_SIGNING_PROPERTIES" }
        testBuildType = "release"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    packaging {
        resources {
            merges += "META-INF/LICENSE*"
            merges += "META-INF/NOTICE*"
        }
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
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/releaseNotices"))
}

val stageProjectNotices = tasks.register<Copy>("stageProjectNotices") {
    from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_LICENSE_BLITZTEXT_MAC.txt"))
    into(layout.buildDirectory.dir("generated/releaseNotices/notices"))
}
tasks.named("preBuild") { dependsOn(stageProjectNotices) }

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
            val expectedFiles = mutableSetOf("assets/qualcomm/runtime-manifest.json")
            for ((name, pin) in pins) {
                val relative = when {
                    name.endsWith(".jar") -> "libs/$name"
                    name.contains("Skel") -> "assets/qualcomm/$name"
                    else -> "jniLibs/arm64-v8a/$name"
                }
                expectedFiles += relative
                val binary = runtime.resolve(relative)
                check(binary.isFile && binary.length() == (pin.getValue("bytes") as Number).toLong()) { "Missing or invalid Qualcomm artifact: $relative" }
                check(sha256(binary) == pin["sha256"]) { "Qualcomm artifact SHA-256 mismatch: $relative" }
            }
            val notices = JsonSlurper().parse(rootProject.file("tools/qualcomm-whisper/runtime-notices.json")) as Map<*, *>
            for ((name, hash) in notices) {
                val relative = "assets/notices/$name"
                expectedFiles += relative
                val notice = runtime.resolve(relative)
                check(notice.isFile && sha256(notice) == hash) { "Missing or altered Qualcomm notice: $relative" }
            }
            val files = runtime.walkTopDown().filter { it.isFile }.map { it.relativeTo(runtime).invariantSeparatorsPath }.toSet()
            check(files == expectedFiles) { "Unexpected or missing files in Qualcomm runtime bundle" }
        }
    }
    tasks.named("preBuild") { dependsOn(verifyRuntime) }
}

val checkFullRelease = tasks.register("checkFullRelease") {
    doLast {
        check(qualcommRuntimeDir != null) { "The full release requires -PqualcommRuntimeDir" }
        check(releaseSigning != null) { "The full release requires TAPSTOP_SIGNING_PROPERTIES" }
    }
}

tasks.register<Copy>("packageTapStop") {
    dependsOn(checkFullRelease, "assembleRelease")
    from(layout.buildDirectory.file("outputs/apk/release/app-release.apk"))
    into(layout.buildDirectory.dir("outputs/apk/tapstop"))
    rename { "TapStop-Android-${android.defaultConfig.versionName}.apk" }
}
