import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    id("com.google.gms.google-services")
}

// eBay Browse API creds live in the gitignored /ebay.properties (never committed). Absent on a fresh
// clone → empty strings, and EbayBrowsePriceProvider degrades to the mock. Real secret stays out of git.
val ebayProps = Properties().apply {
    rootProject.file("ebay.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
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

        buildConfigField("String", "EBAY_CLIENT_ID", "\"${ebayProps.getProperty("EBAY_CLIENT_ID", "")}\"")
        buildConfigField("String", "EBAY_CLIENT_SECRET", "\"${ebayProps.getProperty("EBAY_CLIENT_SECRET", "")}\"")
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
        buildConfig = true   // BuildConfig.DEBUG gates the dev-only LiteRT smoke-test entry
    }
    // The real 283+-item HobbyDB CSV lives in the repo's seed-data/ and is the
    // integration-test fixture — expose it to instrumented tests as an asset.
    sourceSets {
        getByName("androidTest") {
            assets.srcDirs(files("$projectDir/../seed-data"))
        }
    }
    testOptions {
        unitTests {
            // Let JVM unit tests exercise main code that calls into android.jar (e.g. android.util.Log
            // in BenchmarkHarness) — unmocked framework calls return defaults instead of throwing.
            isReturnDefaultValues = true
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

    // App Check debug provider — debug builds only, so it can never reach a distributed build. Bypasses
    // attestation for local dev now that Firebase auto-enforces App Check on the Gemini API. The
    // src/debug installAppCheck() installs it; src/release ships a no-op twin.
    debugImplementation(libs.firebase.appcheck)
    debugImplementation(libs.firebase.appcheck.debug)

    // --- On-device AI (ML Kit GenAI Summarization — genai-summarization sample) ---
    implementation(libs.mlkit.genai.summarization)
    implementation(libs.androidx.concurrent.futures.ktx)   // ListenableFuture.await()

    // --- On-device AI (ML Kit GenAI Prompt API — cascade Stage 2, Gemini Nano multimodal) ---
    // Chosen over the fixed genai-image-description captioner: Gate A (Week 8 Day 1) found the captioner's
    // output safety-classifier deterministically refuses fantasy/horror Funko imagery (ErrorCode 11),
    // while a product-framed Prompt API request passes safety AND extracts identity + Pop number on-device.
    implementation(libs.mlkit.genai.prompt)

    // --- On-device ML (ML Kit Vision — cascade deterministic stages: OCR, segmentation) ---
    implementation(libs.mlkit.text.recognition)   // Latin, bundled model (offline, no download)
    implementation(libs.mlkit.subject.segmentation) // subject mask; model downloaded on first use
    implementation(libs.mlkit.barcode.scanning)   // UPC/EAN decode for the barcode fallback path

    // --- On-device AI (own-model: self-quantized Gemma 3 1B via MediaPipe LLM Inference / LiteRT-LM) ---
    implementation(libs.mediapipe.tasks.genai)

    // --- On-device RAG (EmbeddingGemma-300M on the LiteRT interpreter — Ask Arcana semantic retrieval) ---
    // The interpreter runtime only; the model .tflite is side-loaded (gated Gemma, like the own-model engine)
    // and the tokenizer lives behind the EmbeddingTokenizer seam (resolved separately).
    implementation(libs.litert)

    // --- Data layer ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.commons.csv)

    // --- Background work (weekly price sync) ---
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // --- CameraX (capture flow — Week 9): preview + still capture for the cascade's input frame ---
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
}
