package com.aashishgodambe.arcana.core.ai.benchmark

/**
 * A fixed benchmark prompt. The text is fully self-contained (any grounding context is baked in) so that
 * every engine sees byte-for-byte identical input — the on-device-vs-cloud delta is then a property of the
 * engine, not of what we happened to feed it. [id] is stable for aggregation/persistence; [label] is the UI
 * name.
 */
data class BenchmarkPrompt(
    val id: String,
    val label: String,
    val text: String,
)

/**
 * The default sweep: one short ungrounded prompt and one grounded "collection" prompt, mirroring the two
 * shapes of real "Ask Arcana" traffic. The grounded prompt embeds a small fixed item list inline rather than
 * reading Room, so the measurement is reproducible and identical across engines and runs.
 *
 * Kept intentionally small — the sweep is sequential and on-device is ~3.7 s/call, so every extra prompt adds
 * ~75 s per engine at the default iteration count.
 */
object BenchmarkPrompts {

    val SHORT = BenchmarkPrompt(
        id = "short",
        label = "Short question",
        text = "In one sentence, what is a Funko Pop?",
    )

    val GROUNDED = BenchmarkPrompt(
        id = "grounded",
        label = "Grounded question",
        text = buildString {
            appendLine("You are Arcana, a private assistant for a collectibles portfolio.")
            appendLine("Answer using ONLY these rows. Be concise (1-2 sentences). State dollar values exactly.")
            appendLine()
            appendLine("Items (most valuable first):")
            appendLine("1. Daenerys with Egg — \$690 — series: Game of Thrones (NFT redeemable)")
            appendLine("2. Fire Nation Aang — \$540 — series: Avatar (NFT redeemable)")
            appendLine("3. Daemon Targaryen — \$450 — series: House of the Dragon (NFT redeemable)")
            appendLine()
            append("Current question: What is my most valuable item, and what is it worth?")
        },
    )

    val DEFAULT: List<BenchmarkPrompt> = listOf(SHORT, GROUNDED)
}
