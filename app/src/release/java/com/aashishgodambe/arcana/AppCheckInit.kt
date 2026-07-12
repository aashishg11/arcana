package com.aashishgodambe.arcana

import android.app.Application

/**
 * Release twin of the debug App Check installer — intentionally a no-op. A distributed build would
 * install a real attestation provider (e.g. Play Integrity) here; Arcana is a portfolio app that is
 * not distributed, so this stays empty. Its existence keeps [ArcanaApplication] variant-agnostic:
 * `main` calls [installAppCheck] and each variant supplies its own implementation.
 */
@Suppress("UNUSED_PARAMETER")
fun installAppCheck(app: Application) = Unit
