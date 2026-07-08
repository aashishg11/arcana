package com.aashishgodambe.arcana.core.ai.model

/**
 * Escape hatch for inference routing — features pass [Auto] almost always.
 *
 * - [Auto] / [PreferOnDevice] — try on-device, fall back to cloud on 606. Identical behavior;
 *   [PreferOnDevice] is documentation-only intent at the call site.
 * - [OnlyOnDevice] — offline "Ask Arcana": the caller knows there's no network, so don't let the
 *   service attempt a cloud call and time out.
 * - [OnlyCloud] — benchmark mode, forcing cloud for a direct on-device-vs-cloud comparison.
 */
enum class RoutingHint { Auto, PreferOnDevice, OnlyOnDevice, OnlyCloud }
