plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.google.gms.google-services")
}

android {
    namespace = "com.aashishgodambe.arcana"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aashishgodambe.arcana"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    // The real 283+-item HobbyDB CSV lives in the repo's seed-data/ and is the
    // integration-test fixture — expose it to instrumented tests as an asset.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/../seed-data"))
        }
    }
}

kotlin {
    compilerOptions {
        // Some 2026 libs (e.g. Coil 3.5) ship Kotlin 2.4 metadata, ahead of AGP 9.2's bundled
        // Kotlin 2.2.10 compiler. Consuming their compiled APIs is safe; allow the newer metadata.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

configurations.configureEach {
    resolutionStrategy {
        // Hilt/Dagger's annotation processor reads Kotlin metadata via kotlin-metadata-jvm, whose
        // default here maxes at 2.3.0 — too old for Coil 3.5's Kotlin 2.4 metadata. Bump the reader.
        force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // --- Compose UI ---
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    // --- On-device AI (Gemini Nano via Firebase AI Logic) ---
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.ai)
    implementation(libs.firebase.ai.ondevice)

    // --- On-device AI (ML Kit GenAI Summarization — genai-summarization sample) ---
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.androidx.concurrent.futures.ktx)   // ListenableFuture.await()

    // --- Data layer ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.commons.csv)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
}
