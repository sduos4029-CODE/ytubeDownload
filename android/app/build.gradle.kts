plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "com.flux.downloader"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.flux.downloader"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "2.4.1"
        testInstrumentationRunner = "com.flux.downloader.RuntimeChecks"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }
    val signingStore = providers.environmentVariable("FLUX_SIGNING_STORE").orNull
    if (signingStore != null) signingConfigs.create("continuity") {
        storeFile = file(signingStore)
        storePassword = providers.environmentVariable("FLUX_STORE_PASSWORD").orNull
        keyAlias = providers.environmentVariable("FLUX_KEY_ALIAS").orNull
        keyPassword = providers.environmentVariable("FLUX_KEY_PASSWORD").orNull
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // CI builds unsigned; the existing private key stays outside GitHub.
            if (signingStore != null) signingConfig = signingConfigs.getByName("continuity")
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    packaging { jniLibs { useLegacyPackaging = true } }
}
dependencies {
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.22.3"))
    constraints {
        implementation("commons-io:commons-io:2.22.0") { because("Replace vulnerable legacy transitive version") }
        implementation("org.apache.commons:commons-compress:1.28.0") { because("Replace vulnerable legacy transitive version") }
    }
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.github.junkfood02.youtubedl-android:library:0.18.0")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.18.0")
}
