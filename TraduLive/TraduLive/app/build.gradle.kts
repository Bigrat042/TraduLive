
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tradulive"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tradulive"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"

        // OnePlus 15 : architecture ARM64 uniquement
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false

            // Signé avec la clé de debug
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += "-Xadd-modules=jdk.httpserver"
    }

    // Le lint ne bloque pas la compilation
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        htmlReport = true
        xmlReport = true
    }
}

// Activation du module HTTP du JDK pendant les tests
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    jvmArgs("--add-modules=jdk.httpserver")
}

dependencies {
    // Whisper + VAD sur le téléphone (sherpa-onnx)
    implementation(files("libs/sherpa-onnx.aar"))

    // Traduction hors ligne (Google ML Kit)
    implementation("com.google.mlkit:translate:17.0.3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // Lecture des archives .tar.bz2
    implementation("org.apache.commons:commons-compress:1.27.1")

    // Tests unitaires
    testImplementation("junit:junit:4.13.2")
}
