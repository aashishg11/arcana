package com.aashishgodambe.etharness;

import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pytorch.executorch.extension.llm.LlmCallback;
import org.pytorch.executorch.extension.llm.LlmGenerationConfig;
import org.pytorch.executorch.extension.llm.LlmModule;

import java.io.File;

/**
 * Week 5 / Day 3, rung 2: run a quantized tiny LLM on the Pixel.
 *
 * One @Test per precision so a failure in one variant doesn't hide the others.
 * Greedy decoding (temperature 0) so output is deterministic and the precisions
 * are actually comparable -- sampling would confound quality with luck.
 *
 * Real tokens/sec come from LlmCallback.onStats(), not from wall-clock around
 * the callback, which would include the JNI string hop per token.
 */
@RunWith(AndroidJUnit4.class)
public class LlmRunnerTest {

    private static final String TAG = "ETLLM";
    private static final String DIR = "/data/local/tmp/";
    private static final String PROMPT = "Q: What is the capital of France?\nA:";
    private static final int MAX_NEW_TOKENS = 64;
    /** prompt (~12 tok) + generated. The real bound; see the note at the config. */
    private static final int SEQ_LEN = 80;

    private void runVariant(String variant) {
        String pte = DIR + variant + "/model.pte";
        String tok = DIR + variant + "/tokenizer.json";

        File pteFile = new File(pte);
        File tokFile = new File(tok);
        assertTrue("missing " + pte + " (adb push it?)", pteFile.exists());
        assertTrue("missing " + tok, tokFile.exists());

        Log.i(TAG, "======== " + variant + " ========");
        Log.i(TAG, "  model.pte bytes : " + pteFile.length());

        // NOTE: do NOT use LlmModuleConfig.create()...build() here. Its dataPath
        // defaults to "" (for models whose weights live in a separate .ptd), and the
        // native layer tries to open that empty path -- "Failed to open : No such file
        // or directory" -- then aborts the whole process. The 3-arg constructor has no
        // dataPath, so it takes the single-file .pte route.
        LlmModule module = new LlmModule(pte, tok, 0f /* greedy */);

        long t0 = System.nanoTime();
        module.load();
        Log.i(TAG, "  load ms         : " + (System.nanoTime() - t0) / 1_000_000);

        StringBuilder out = new StringBuilder();
        final String[] stats = {"(none)"};
        final int[] errors = {0};

        LlmCallback cb = new LlmCallback() {
            @Override
            public void onResult(String token) {
                out.append(token);
            }

            @Override
            public void onStats(String s) {
                stats[0] = s;
            }

            @Override
            public void onError(int code, String msg) {
                errors[0]++;
                Log.e(TAG, "  onError " + code + ": " + msg);
            }
        };

        // maxNewTokens() is NOT honoured by this runner -- with it set to 64 the model
        // still produced 499 tokens. seqLen is what actually bounds generation
        // (total context: prompt + generated), so bound it there.
        LlmGenerationConfig gen = LlmGenerationConfig.create()
                .echo(false)
                .seqLen(SEQ_LEN)
                .maxNewTokens(MAX_NEW_TOKENS)
                .temperature(0f)
                .build();

        long g0 = System.nanoTime();
        module.generate(PROMPT, gen, cb);
        long wallMs = (System.nanoTime() - g0) / 1_000_000;

        String text = out.toString();
        Log.i(TAG, "  wall ms         : " + wallMs);
        Log.i(TAG, "  stats           : " + stats[0]);
        Log.i(TAG, "  chars out       : " + text.length());
        String shown = text.length() > 260 ? text.substring(0, 260) + "..." : text;
        Log.i(TAG, "  OUTPUT >>>" + shown.replace("\n", "\\n") + "<<<");

        module.close();

        assertTrue(variant + ": generation errored", errors[0] == 0);
        assertTrue(variant + ": produced no output", text.trim().length() > 0);
    }

    /** Baseline: no quantization at all. */
    @Test public void fp32() { runVariant("smollm2_fp32"); }

    /**
     * Weight-only int4. XNNPACK has no weight-only linear kernel, so the dq is
     * constant-folded back to fp32 at lowering: this .pte is the same size as fp32
     * and should generate byte-identical text. Kept as the control that proves the
     * no-op, not as a real precision.
     */
    @Test public void int4WeightOnly_isANoOp() { runVariant("smollm2_4w"); }

    /** INT8 dynamic-activation + int8 weights -- the real INT8 point. */
    @Test public void int8DynAct() { runVariant("smollm2_8da8w"); }

    /** INT8 dynamic-activation + int4 weights -- the real INT4 point. */
    @Test public void int4DynAct() { runVariant("smollm2_8da4w"); }

    /** Same, plus an int4 embedding table: the smallest artifact. */
    @Test public void int4DynActInt4Embedding() { runVariant("smollm2_8da4w_emb4w"); }
}
