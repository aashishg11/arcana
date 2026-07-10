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
 * Week 5 / Day 4, rung 3: Gemma 3 1B, quantized by me, generating on the Pixel.
 *
 * Unlike Day 3's SmolLM2 run, this applies Gemma's chat template. SmolLM2's raw-prompt
 * output degenerated into loops, and while that was greedy decoding on a 135M model
 * rather than a quantization artifact, an instruction-tuned model given its actual
 * template is the fair test of "does coherent generation work."
 */
@RunWith(AndroidJUnit4.class)
public class GemmaRunnerTest {

    private static final String TAG = "ETGEMMA";
    private static final String DIR = "/data/local/tmp/";

    /**
     * Gemma 3 chat template, including the leading <bos>.
     *
     * The runner does NOT prepend BOS: neither LlmGenerationConfig.numBos() (ignored,
     * like maxNewTokens) nor the 3-arg LlmModule ctor adds one -- "The capital of
     * France is" tokenized to exactly 5 tokens. But the tokenizer DOES parse special
     * tokens out of the prompt text (<start_of_turn> came through as one token), so
     * the fix is to write <bos> into the prompt string itself.
     */
    private static final String PROMPT =
            "<bos><start_of_turn>user\nWhat is the capital of France? Answer in one sentence.<end_of_turn>\n"
                    + "<start_of_turn>model\n";

    private static final int SEQ_LEN = 128;   // maxNewTokens is ignored; seqLen is the real bound
    private static final int MAX_NEW_TOKENS = 96;

    /**
     * Gemma REQUIRES <bos> at position 0. The runner prepends none by default -- a
     * 5-word prompt tokenized to exactly 5 tokens, proving no BOS was added -- and
     * without it Gemma degenerates into " is is is ..." regardless of quantization.
     */
    private static final int NUM_BOS = 1;

    private void runVariant(String variant) {
        assertAnswers(variant, PROMPT, "chat-template");
    }

    /** Returns the generated text so callers can assert on it. */
    private String runPrompt(String variant, String prompt, String label) {
        String pte = DIR + variant + "/model.pte";
        String tok = DIR + variant + "/tokenizer.json";

        File pteFile = new File(pte);
        assertTrue("missing " + pte, pteFile.exists());
        assertTrue("missing " + tok, new File(tok).exists());

        Log.i(TAG, "======== " + variant + "  [" + label + "] ========");
        Log.i(TAG, "  model.pte bytes : " + pteFile.length());
        Log.i(TAG, "  prompt          : " + prompt.replace("\n", "\\n"));

        // 3-arg ctor: LlmModuleConfig's dataPath defaults to "" and aborts natively.
        LlmModule module = new LlmModule(pte, tok, 0f /* greedy */);

        long t0 = System.nanoTime();
        module.load();
        Log.i(TAG, "  load ms         : " + (System.nanoTime() - t0) / 1_000_000);

        StringBuilder out = new StringBuilder();
        final String[] stats = {"(none)"};
        final int[] errors = {0};

        LlmCallback cb = new LlmCallback() {
            @Override public void onResult(String token) { out.append(token); }
            @Override public void onStats(String s) { stats[0] = s; }
            @Override public void onError(int code, String msg) {
                errors[0]++;
                Log.e(TAG, "  onError " + code + ": " + msg);
            }
        };

        LlmGenerationConfig gen = LlmGenerationConfig.create()
                .echo(false)
                .seqLen(SEQ_LEN)
                .maxNewTokens(MAX_NEW_TOKENS)
                .temperature(0f)
                .numBos(NUM_BOS)
                .build();

        long g0 = System.nanoTime();
        module.generate(prompt, gen, cb);
        long wallMs = (System.nanoTime() - g0) / 1_000_000;

        String text = out.toString();
        Log.i(TAG, "  wall ms         : " + wallMs);
        Log.i(TAG, "  stats           : " + stats[0]);
        String shown = text.length() > 400 ? text.substring(0, 400) + "..." : text;
        Log.i(TAG, "  OUTPUT >>>" + shown.replace("\n", "\\n") + "<<<");

        module.close();

        assertTrue(variant + ": generation errored", errors[0] == 0);
        assertTrue(variant + ": produced no output", text.trim().length() > 0);
        return text;
    }

    private void assertAnswers(String variant, String prompt, String label) {
        String text = runPrompt(variant, prompt, label);
        assertTrue(variant + " [" + label + "]: expected 'Paris' in a coherent answer",
                text.toLowerCase().contains("paris"));
    }

    /**
     * Diagnostic: no chat template, no special tokens -- a pure completion prompt,
     * exactly the shape SmolLM2 succeeded with. If this produces coherent text while
     * the chat-template run emits only <end_of_turn>, the fault is in prompt/special
     * -token handling, NOT in the quantized weights.
     */
    @Test public void plainPrompt_emb4w() {
        assertAnswers("gemma3_1b_8da4w_emb4w", "<bos>The capital of France is", "plain+bos");
    }

    /**
     * Control, and it asserts the DEGENERATION on purpose: the identical prompt without
     * <bos> collapses to " is is is ...". Same .pte, same weights, one token of
     * difference -- so this pins the failure on prompt construction, not quantization.
     * If a future runtime starts prepending BOS itself, this test goes red and tells us.
     */
    @Test public void plainPromptNoBos_degenerates() {
        String text = runPrompt("gemma3_1b_8da4w_emb4w", "The capital of France is", "plain-no-bos");
        assertTrue("without <bos> Gemma should degenerate, but it answered coherently — "
                        + "the runtime may now prepend BOS; revisit the prompt construction",
                !text.toLowerCase().contains("paris"));
    }

    /** The real target: INT4 linears + INT4 tied embedding/lm_head. */
    @Test public void int4DynActInt4Embedding() { runVariant("gemma3_1b_8da4w_emb4w"); }

    /** INT4 linears, fp32 embedding -- isolates what --qembedding buys on a 262k vocab. */
    @Test public void int4DynActOnly() { runVariant("gemma3_1b_8da4w"); }
}
