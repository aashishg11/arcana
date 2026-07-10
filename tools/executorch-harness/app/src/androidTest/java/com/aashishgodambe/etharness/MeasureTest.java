package com.aashishgodambe.etharness;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pytorch.executorch.extension.llm.LlmCallback;
import org.pytorch.executorch.extension.llm.LlmGenerationConfig;
import org.pytorch.executorch.extension.llm.LlmModule;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Day 5: the measurement pass. One @Test per model so each runs in a FRESH process
 * (instrumentation restarts the app per invocation) -- required because peak RSS
 * (/proc/self/status VmHWM) is monotonic within a process and would otherwise carry
 * over from the previous model.
 *
 * Reports, per model: .pte size, load ms, peak RSS, and decode/prefill tok/s over
 * REPEATS measured generations after one warm-up. tok/s comes from the runtime's own
 * onStats(), never from wall-clock around the JNI callback.
 */
@RunWith(AndroidJUnit4.class)
public class MeasureTest {

    private static final String TAG = "ETMEASURE";
    private static final String DIR = "/data/local/tmp/";
    private static final int SEQ_LEN = 128;
    private static final int REPEATS = 3;

    /** Every model gets a BOS; Gemma requires it, SmolLM2 tolerates it. */
    private static final String PROMPT = "<bos>The capital of France is";
    /** SmolLM2's tokenizer has no <bos> literal; use its own plain prompt. */
    private static final String PROMPT_SMOL = "Q: What is the capital of France?\nA:";

    private static long peakRssKb() {
        try (BufferedReader r = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("VmHWM:")) {
                    return Long.parseLong(line.replaceAll("[^0-9]", ""));
                }
            }
        } catch (IOException ignored) {
        }
        return -1;
    }

    private void measure(String variant, String prompt) {
        String pte = DIR + variant + "/model.pte";
        String tok = DIR + variant + "/tokenizer.json";
        File f = new File(pte);
        assertTrue("missing " + pte, f.exists());

        Log.i(TAG, "======== " + variant + " ========");
        Log.i(TAG, "  pte_bytes=" + f.length());
        Log.i(TAG, "  rss_before_kb=" + peakRssKb());

        long t0 = System.nanoTime();
        LlmModule module = new LlmModule(pte, tok, 0f);
        module.load();
        Log.i(TAG, "  load_ms=" + (System.nanoTime() - t0) / 1_000_000);
        Log.i(TAG, "  rss_after_load_kb=" + peakRssKb());

        LlmGenerationConfig gen = LlmGenerationConfig.create()
                .echo(false).seqLen(SEQ_LEN).temperature(0f).build();

        final List<String> statsSeen = new ArrayList<>();
        LlmCallback cb = new LlmCallback() {
            @Override public void onResult(String t) { }
            @Override public void onStats(String s) { statsSeen.add(s); }
            @Override public void onError(int c, String m) { Log.e(TAG, "  onError " + c + ": " + m); }
        };

        // one warm-up, then REPEATS measured runs
        module.generate(prompt, gen, cb);
        statsSeen.clear();

        for (int i = 0; i < REPEATS; i++) {
            try { module.resetContext(); } catch (Throwable ignored) { }
            module.generate(prompt, gen, cb);
            Log.i(TAG, "  run" + i + "_stats=" + statsSeen.get(statsSeen.size() - 1));
        }

        Log.i(TAG, "  rss_peak_kb=" + peakRssKb());
        module.close();
        assertTrue(variant + ": no stats emitted", !statsSeen.isEmpty());
    }

    @Test public void smollm2_8da4w_emb4w()  { measure("smollm2_8da4w_emb4w", PROMPT_SMOL); }
    @Test public void smollm2_8da4w()        { measure("smollm2_8da4w", PROMPT_SMOL); }
    @Test public void smollm2_8da8w()        { measure("smollm2_8da8w", PROMPT_SMOL); }
    @Test public void gemma_8da4w_emb4w()    { measure("gemma3_1b_8da4w_emb4w", PROMPT); }
    @Test public void gemma_8da4w()          { measure("gemma3_1b_8da4w", PROMPT); }
    @Test public void gemma_8da8w_emb4w()    { measure("gemma3_1b_8da8w_emb4w", PROMPT); }
}
