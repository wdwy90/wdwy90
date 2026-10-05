import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Optional: bake a Places API key into the build from local.properties
// (PLACES_API_KEY=...). If absent, the app asks for the key on first launch.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.wdwy90.pullupmenu"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wdwy90.pullupmenu"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1"
        buildConfigField(
            "String", "PLACES_API_KEY",
            "\"${localProps.getProperty("PLACES_API_KEY", System.getenv("PLACES_API_KEY") ?: "")}\""
        )
    }

    buildFeatures { buildConfig = true }

    // chain_menus.json is shared with the iOS app.
    sourceSets["main"].assets.srcDir("../../shared")

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.car.app:app:1.7.0")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1") // satisfies activity-result lint check
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")
    implementation("com.google.android.gms:play-services-location:21.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
