import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing from keystore.properties (git-ignored). Without it, release falls back to the debug key
// so the APK still installs on the emulator; a contest release should be built with the real keystore.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.carecabin"
    compileSdk = 34
    useLibrary("android.car") // CarUxRestrictions (provided by AAOS at runtime)
    defaultConfig {
        applicationId = "app.carecabin"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "0.3.0-mvp"
    }
    flavorDimensions += "platform"
    productFlavors {
        // Runs anywhere: simulator vehicle, Android TTS, keyword buttons.
        create("sim") { dimension = "platform"; applicationIdSuffix = ".sim"; versionNameSuffix = "-sim" }
        // Contest build: the five Pleos Connect SDKs, falling back per part when a service is missing.
        create("pleos") { dimension = "platform" }
    }
    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false // SDK reflection safety for the MVP (development plan 6-5)
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    buildFeatures { compose = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric extracts its native graphics DLL to java.io.tmpdir; keep that path ASCII on Windows.
        unitTests.all { t ->
            val tmp = layout.buildDirectory.dir("robolectric-tmp").get().asFile.apply { mkdirs() }
            t.systemProperty("java.io.tmpdir", tmp.absolutePath)
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":engine"))
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Screen renders on the JVM (Robolectric native graphics) — sim flavor only.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    "pleosImplementation"("ai.pleos.playground:Vehicle:2.3.2")
    "pleosImplementation"("ai.pleos.playground:NaviHelper:3.0.5")
    "pleosImplementation"("ai.pleos.playground:FusedLocation:1.1.3")
    "pleosImplementation"("ai.pleos.playground:TextToSpeech:3.3.1")
    "pleosImplementation"("ai.pleos.playground:SpeechToText:3.3.1")
}
