package com.aashishgodambe.arcana

import android.app.Application
import android.util.Log
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * Debug-only App Check installer. Firebase auto-enforces App Check on the Gemini API as of early July
 * 2026, so local-dev cloud calls are rejected unless a provider is installed. The debug provider
 * bypasses attestation using a per-install debug token: on first run it logs the token (tag
 * `DebugAppCheckProvider`), which must be registered once in the Firebase console under
 * App Check → Apps → (this app) → Manage debug tokens.
 *
 * This lives in the `debug` source set; the `release` source set ships a no-op twin with the same
 * signature, so the debug provider never compiles into a distributed build. Called from
 * [ArcanaApplication.onCreate], which runs after Firebase's init provider, so the default
 * [FirebaseAppCheck] instance is available.
 */
fun installAppCheck(app: Application) {
    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
        DebugAppCheckProviderFactory.getInstance(),
    )
    Log.i("ArcanaAppCheck", "Debug App Check provider installed — register the debug token from logcat.")
}
