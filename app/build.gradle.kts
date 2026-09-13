import java.util.Properties

// AGP 9 has built-in Kotlin support, so org.jetbrains.kotlin.android is not applied here.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// API keys are personal credentials, so they live in local.properties (untracked) rather than in
// the build file. Missing keys are not a build failure — the app reports them only when cloud
// transcription is used.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val geminiApiKey: String = localProperties.getProperty("gemini.api.key").orEmpty()
val openAiApiKey: String = localProperties.getProperty("openai.api.key").orEmpty()

android {
    namespace = "com.eirmon.cutly"
    compileSdk = 37
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.eirmon.cutly"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        // sherpa-onnx ships native libraries for four ABIs and most of the AAR's 46 MB is the
        // three nobody runs on. Every Android phone since 2019 is arm64, and the emulator images
        // used here are x86_64, so those two are kept and the rest dropped.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // String constants survive R8 untouched, so a key here would ship in every APK.
            // Release builds carry no key; users paste their own in Settings.
            buildConfigField("String", "GEMINI_API_KEY", "\"\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            buildConfigField("String", "GEMINI_API_KEY", "\"$geminiApiKey\"")
            buildConfigField("String", "OPENAI_API_KEY", "\"$openAiApiKey\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

/**
 * Terminal dev loop: `gradlew devRun -t` rebuilds, reinstalls and relaunches on every save.
 * Not hot reload — a full ~20s cycle — but it needs no IDE open.
 */
val adbExecutable = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
    .map { "$it/platform-tools/adb.exe" }
    .orElse("adb")

tasks.register<Exec>("devRun") {
    group = "install"
    description = "Installs the debug build and relaunches it on the connected device."
    dependsOn("installDebug")
    commandLine(
        adbExecutable.get(),
        "shell", "am", "start",
        "-n", "com.eirmon.cutly.debug/com.eirmon.cutly.MainActivity"
    )
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.video)
    implementation(libs.androidx.camera.compose)

    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.media3.effect)

    // Offline speech recognition, for the languages the phone's own recogniser does not have.
    // The "@aar" is required: the ivy repository serves a bare artifact with no POM, so Gradle
    // has to be told the extension rather than being left to infer it from metadata.
    implementation("${libs.sherpa.onnx.get()}@aar")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // The android.jar used for unit tests stubs org.json out; the real implementation shadows it
    // so the Gemini response parser can be tested off-device.
    testImplementation(libs.json)
}
