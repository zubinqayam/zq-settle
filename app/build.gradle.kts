import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Key sources, in order: gitignored local.properties, then the PRESAGE_API_KEY environment variable.
// Neither is committed. If both are empty the build is keyless and the app runs timer-only.
// The placeholder from local.properties.example counts as "no key".
val keyPlaceholder = "YOUR_PRESAGE_API_KEY_HERE"
val presageApiKey: String = listOf(localProps.getProperty("PRESAGE_API_KEY"), System.getenv("PRESAGE_API_KEY"))
    .map { it?.trim().orEmpty() }
    .firstOrNull { it.isNotEmpty() && it != keyPlaceholder }
    .orEmpty()

android {
    namespace = "com.zq.commprep"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zq.commprep"
        minSdk = 28
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.0-prep2-fpsgate-lowres"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // Private keyed builds only: the key comes from the gitignored local.properties or the
            // PRESAGE_API_KEY environment variable. A key bundled in an APK may be extractable;
            // it is not secure. Never share or publish a keyed APK.
            buildConfigField("String", "PRESAGE_API_KEY", "\"${presageApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        }
        release {
            // 0.2.0: release builds are always keyless (timer-only; the app shows that measurement is
            // unavailable). Nothing from local.properties is packaged into a release APK.
            buildConfigField("String", "PRESAGE_API_KEY", "\"\"")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.camera:camera-view:1.6.0")
    implementation("androidx.camera:camera-camera2:1.6.0")
    implementation("androidx.camera:camera-lifecycle:1.6.0")
    compileOnly("com.google.guava:guava:33.3.1-android")
    implementation("com.presagetech:smartspectra:3.4.0")
    implementation("androidx.startup:startup-runtime:1.1.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
