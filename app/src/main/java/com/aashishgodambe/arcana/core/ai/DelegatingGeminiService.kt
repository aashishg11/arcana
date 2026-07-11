package com.aashishgodambe.arcana.core.ai

import com.aashishgodambe.arcana.core.ai.model.AskEngine
import com.aashishgodambe.arcana.core.ai.model.InferenceResult
import com.aashishgodambe.arcana.core.ai.model.RoutingHint
import kotlinx.coroutines.flow.Flow

/**
 * The [GeminiService] actually injected app-wide. It makes the "swappable AI backends behind one
 * interface" abstraction a *live, user-controlled* feature: on the default [RoutingHint.Auto] path it
 * routes to whichever engine the Settings picker selected, so flipping the picker switches Ask Arcana's
 * engine mid-app with no call-site change.
 *
 * Explicit hints bypass the picker — they pin one engine for a direct comparison (the benchmark harness
 * drives every engine this way, keeping each a single column on this one seam):
 * - [RoutingHint.OnlyOnDevice] / [RoutingHint.OnlyCloud] → [hybrid] (Nano / cloud).
 * - [RoutingHint.OnlyOwnModel] → [ownModel] (the self-quantized Gemma).
 *
 * [selectedEngine] is read fresh per call (a thunk over the persisted setting), so a selection change
 * takes effect on the very next question without re-wiring anything.
 */
class DelegatingGeminiService(
    private val hybrid: GeminiService,
    private val ownModel: OwnModelEngine,
    private val selectedEngine: () -> AskEngine,
) : GeminiService {

    override fun generateText(prompt: String, routingHint: RoutingHint): Flow<InferenceResult> =
        when (routingHint) {
            RoutingHint.OnlyOnDevice -> hybrid.generateText(prompt, RoutingHint.OnlyOnDevice)
            RoutingHint.OnlyCloud -> hybrid.generateText(prompt, RoutingHint.OnlyCloud)
            RoutingHint.OnlyOwnModel -> ownModel.generateText(prompt, RoutingHint.OnlyOwnModel)
            RoutingHint.Auto, RoutingHint.PreferOnDevice -> routeBySelection(prompt)
        }

    private fun routeBySelection(prompt: String): Flow<InferenceResult> = when (selectedEngine()) {
        AskEngine.Nano -> hybrid.generateText(prompt, RoutingHint.PreferOnDevice)
        AskEngine.Cloud -> hybrid.generateText(prompt, RoutingHint.OnlyCloud)
        // Own-model selected but the side-loaded file vanished (e.g. cleared) → fall back to Nano rather
        // than error. The picker greys the option when absent, so this is a belt-and-suspenders guard.
        AskEngine.OwnModel ->
            if (ownModel.isModelAvailable()) ownModel.generateText(prompt, RoutingHint.OnlyOwnModel)
            else hybrid.generateText(prompt, RoutingHint.PreferOnDevice)
    }
}
