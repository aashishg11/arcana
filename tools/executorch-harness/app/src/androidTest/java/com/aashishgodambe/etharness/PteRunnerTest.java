package com.aashishgodambe.etharness;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.pytorch.executorch.EValue;
import org.pytorch.executorch.Module;
import org.pytorch.executorch.Tensor;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Week 5 / Day 2, rung 1: run a MobileNetV2 .pte on the Pixel and check the answer
 * against logits computed by eager PyTorch on the host.
 *
 * Artifacts are adb push'ed to /data/local/tmp and chmod 644'd. That directory is
 * drwxrwx--x, so an app process can traverse it and open a world-readable file by
 * exact path, even though it cannot list the directory.
 */
@RunWith(AndroidJUnit4.class)
public class PteRunnerTest {

    private static final String TAG = "ETHARNESS";
    private static final String DIR = "/data/local/tmp/";

    private static final int WARMUP = 3;
    private static final int ITERS = 20;
    private static final int EXPECTED_TOP1 = 92; // computed on host, eager PyTorch

    private static float[] readFloats(String path) throws IOException {
        File f = new File(path);
        byte[] raw = new byte[(int) f.length()];
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            in.readFully(raw);
        }
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        float[] out = new float[raw.length / 4];
        bb.asFloatBuffer().get(out);
        return out;
    }

    private static int argmax(float[] a) {
        int best = 0;
        for (int i = 1; i < a.length; i++) if (a[i] > a[best]) best = i;
        return best;
    }

    private static long medianMs(long[] nanos) {
        long[] c = nanos.clone();
        Arrays.sort(c);
        return c[c.length / 2] / 1_000_000;
    }

    private void runVariant(String pteName) throws IOException {
        float[] input = readFloats(DIR + "mobilenet_input.bin");
        float[] expected = readFloats(DIR + "mobilenet_expected.bin");
        assertEquals("input element count", 1 * 3 * 224 * 224, input.length);
        assertEquals("expected element count", 1000, expected.length);

        long loadStart = System.nanoTime();
        Module module = Module.load(DIR + pteName);
        long loadMs = (System.nanoTime() - loadStart) / 1_000_000;

        Tensor in = Tensor.fromBlob(input, new long[]{1, 3, 224, 224});

        // correctness first, then timing
        EValue[] res = module.forward(EValue.from(in));
        float[] logits = res[0].toTensor().getDataAsFloatArray();
        assertEquals("logit count", 1000, logits.length);

        float maxErr = 0f;
        for (int i = 0; i < logits.length; i++) {
            maxErr = Math.max(maxErr, Math.abs(logits[i] - expected[i]));
        }
        int top1 = argmax(logits);

        for (int i = 0; i < WARMUP; i++) module.forward(EValue.from(in));

        long[] times = new long[ITERS];
        for (int i = 0; i < ITERS; i++) {
            long t = System.nanoTime();
            module.forward(EValue.from(in));
            times[i] = System.nanoTime() - t;
        }

        Log.i(TAG, "==== " + pteName + " ====");
        Log.i(TAG, "  load ms          : " + loadMs);
        Log.i(TAG, "  top-1 index      : " + top1 + " (expected " + EXPECTED_TOP1 + ")");
        Log.i(TAG, "  max abs err      : " + maxErr);
        Log.i(TAG, "  warm median ms   : " + medianMs(times) + "  (n=" + ITERS + ")");
        Log.i(TAG, "  warm min ms      : " + (Arrays.stream(times).min().getAsLong() / 1_000_000));

        assertEquals(pteName + ": top-1 must match host eager PyTorch", EXPECTED_TOP1, top1);
        assertTrue(pteName + ": logits diverged, maxErr=" + maxErr, maxErr < 1e-2f);
    }

    @Test
    public void xnnpackDelegated() throws IOException {
        runVariant("mobilenet_v2_xnnpack.pte");
    }

    @Test
    public void portableNoDelegate() throws IOException {
        runVariant("mobilenet_v2_portable.pte");
    }
}
