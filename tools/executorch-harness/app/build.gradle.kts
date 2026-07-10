plugins {
    id("com.android.application")
}

android {
    namespace = "com.aashishgodambe.etharness"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.aashishgodambe.etharness"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The ExecuTorch AAR ships arm64-v8a (and x86_64) .so files; the Pixel is arm64.
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // Must match the exporter version (executorch 1.3.1 in WSL) so the .pte
    // schema the runtime expects is the schema we wrote.
    implementation("org.pytorch:executorch-android:1.3.1")

    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}
