package com.aashishgodambe.arcana.core.ai.rag

import com.aashishgodambe.arcana.core.ai.rag.QueryRouter.Route
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Week-11 retrieval eval — the **router-path** scorer, and the one accuracy suite that is fully
 * reproducible from a clean checkout: pure [QueryRouter.classify] over the committed labeled query set,
 * no device, no model, no cloud. Reads the SAME committed fixture the instrumented structured/semantic
 * suite reads (`androidTest/assets/eval/query_fixtures.tsv`) so there is one source of truth.
 *
 * This is a **measurement**, not a regression gate: it prints per-query results and lists every misroute
 * individually (the plan's requirement), scores accuracy over the whole set and over the non-adversarial
 * subset, and asserts only a loose floor so a catastrophic router regression still trips the build without
 * a single expected-misroute turning the suite red. See EVAL_METHODOLOGY.md §1.2.
 */
class RouterAccuracyEvalTest {

    private data class Fixture(
        val query: String,
        val expectedRoute: String,
        val answerKind: String,
        val groundTruth: String,
        val notes: String,
    )

    @Test
    fun scores_router_path_accuracy_over_the_labeled_set() {
        val fixtures = loadFixtures()
        assertTrue("no query fixtures loaded — check the committed TSV path", fixtures.isNotEmpty())

        var correct = 0
        val misroutes = mutableListOf<String>()
        val rows = mutableListOf<String>()

        for (f in fixtures) {
            val got = routeName(QueryRouter.classify(f.query))
            val hit = got == f.expectedRoute
            if (hit) correct++ else misroutes.add("  “${f.query}”  →  got $got  ·  expected ${f.expectedRoute}")
            rows.add("  [${if (hit) "OK " else "XX"}] ${f.query.padEnd(40)} $got".let {
                if (hit) it else "$it  (expected ${f.expectedRoute})"
            })
        }

        // Adversarial baits (answerKind = "router") are EXPECTED to expose the keyword router's limits;
        // report the "real" subset separately so the honest headline isn't dragged down by them.
        val real = fixtures.filter { it.answerKind != "router" }
        val realCorrect = real.count { routeName(QueryRouter.classify(it.query)) == it.expectedRoute }

        println("\n===== Router-path accuracy (Week-11 eval) =====")
        rows.forEach(::println)
        println("\n----- misroutes (${misroutes.size}) -----")
        if (misroutes.isEmpty()) println("  (none)") else misroutes.forEach(::println)
        println(
            "\nAll queries:        $correct/${fixtures.size} " +
                "(${pct(correct, fixtures.size)})",
        )
        println(
            "Non-adversarial:    $realCorrect/${real.size} " +
                "(${pct(realCorrect, real.size)})  ← the honest headline",
        )
        println(
            "Adversarial baits:  ${fixtures.size - real.size} queries, " +
                "expected to stress the keyword router",
        )
        println("================================================\n")

        // Loose regression floor only — the deliverable is the printed table, not this bound.
        assertTrue(
            "router accuracy collapsed to $correct/${fixtures.size} — investigate a router regression",
            correct.toDouble() / fixtures.size >= 0.70,
        )
    }

    private fun routeName(route: Route): String = when (route) {
        is Route.Count -> "Count"
        is Route.AddedInYear -> "AddedInYear"
        Route.NftRedeemable -> "NftRedeemable"
        Route.MostValuable -> "MostValuable"
        Route.FollowUp -> "FollowUp"
        is Route.Semantic -> "Semantic"
    }

    private fun pct(n: Int, d: Int): String = if (d == 0) "n/a" else "%.0f%%".format(100.0 * n / d)

    /**
     * Read the committed query fixtures. JVM unit tests run with the working dir at the `:app` module root,
     * so the androidTest asset is reachable by a module-relative path; try a couple of candidates and fail
     * loudly (with the resolved path) rather than silently skipping a missing committed fixture.
     */
    private fun loadFixtures(): List<Fixture> {
        val candidates = listOf(
            "src/androidTest/assets/eval/query_fixtures.tsv",
            "app/src/androidTest/assets/eval/query_fixtures.tsv",
        )
        val file = candidates.map(::File).firstOrNull { it.exists() }
            ?: error("query_fixtures.tsv not found; tried ${candidates.map { File(it).absolutePath }}")

        val lines = file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        // First surviving line is the header.
        return lines.drop(1).mapNotNull { line ->
            val c = line.split("\t")
            if (c.size < 3) return@mapNotNull null
            Fixture(
                query = c[0].trim(),
                expectedRoute = c[1].trim(),
                answerKind = c[2].trim(),
                groundTruth = c.getOrNull(3)?.trim().orEmpty(),
                notes = c.getOrNull(4)?.trim().orEmpty(),
            )
        }
    }
}
