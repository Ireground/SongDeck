package com.songdeck.app;

/**
 * Changes pitch by a few cents while keeping the clip the same length:
 * first a WSOLA time-stretch, then resampling back to the original length.
 */
public final class PitchShift {

    public static float[] process(short[] src, int start, int len, int cents) {
        float[] x = new float[len];
        for (int i = 0; i < len; i++) {
            int s = start + i;
            x[i] = s < src.length ? src[s] / 32768f : 0f;
        }
        if (cents == 0 || len < 4096) return x;
        double r = Math.pow(2, cents / 1200.0);
        float[] y = stretch(x, r);
        float[] out = new float[len];
        for (int i = 0; i < len; i++) {
            double p = i * r;
            int a = (int) p;
            if (a + 1 >= y.length) break;
            float f = (float) (p - a);
            out[i] = y[a] + (y[a + 1] - y[a]) * f;
        }
        return out;
    }

    /** Time-stretch so the output is about factor times as long. */
    static float[] stretch(float[] x, double factor) {
        final int W = 1536, HS = W / 2, TOL = 384;
        int outLen = (int) (x.length * factor) + W * 2;
        float[] y = new float[outLen];
        float[] wsum = new float[outLen];
        float[] win = new float[W];
        for (int i = 0; i < W; i++) win[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / W));
        double ha = HS / factor;
        int prev = 0;
        for (int k = 0; ; k++) {
            int outPos = k * HS;
            if (outPos + W > outLen) break;
            int nominal = (int) Math.round(k * ha);
            if (nominal >= x.length) break;
            int best = nominal;
            if (k > 0) {
                int target = prev + HS; // where the previous frame would naturally continue
                double bestC = -Double.MAX_VALUE;
                for (int d = -TOL; d <= TOL; d += 2) {
                    int c = nominal + d;
                    if (c < 0 || c + W > x.length) continue;
                    double s = 0;
                    for (int i = 0; i < W; i += 6) {
                        int ti = target + i;
                        if (ti >= x.length) break;
                        s += x[ti] * x[c + i];
                    }
                    if (s > bestC) { bestC = s; best = c; }
                }
            }
            for (int i = 0; i < W; i++) {
                int si = best + i;
                float v = si < x.length ? x[si] : 0f;
                y[outPos + i] += v * win[i];
                wsum[outPos + i] += win[i];
            }
            prev = best;
        }
        for (int i = 0; i < outLen; i++) if (wsum[i] > 1e-3f) y[i] /= wsum[i];
        return y;
    }
}
