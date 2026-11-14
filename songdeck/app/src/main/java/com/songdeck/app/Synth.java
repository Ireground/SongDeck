package com.songdeck.app;

import java.util.Random;

/** Small polyphonic synth with three sounds: piano, supersaw and organ. */
public final class Synth {

    private static final int SR = Project.SR;
    private static final int TABLE = 4096;
    private static final float[] SINE = new float[TABLE + 1];
    static {
        for (int i = 0; i <= TABLE; i++) SINE[i] = (float) Math.sin(2 * Math.PI * i / TABLE);
    }

    private static float sin(double phase) { // phase in cycles
        double p = phase - Math.floor(phase);
        double x = p * TABLE;
        int i = (int) x;
        float f = (float) (x - i);
        return SINE[i] + (SINE[i + 1] - SINE[i]) * f;
    }

    private static final int MAX_VOICES = 24;
    private static final int PARTIALS = 10;
    private static final double[] SAW_DETUNE = {-0.19, -0.11, -0.04, 0, 0.04, 0.11, 0.19}; // semitones
    private static final float[] SAW_PAN = {-0.9f, 0.6f, -0.4f, 0f, 0.4f, -0.6f, 0.9f};
    private static final double[] ORGAN_RATIO = {0.5, 1, 1.5, 2, 3, 4, 6};
    private static final float[] ORGAN_LEVEL = {0.8f, 1f, 0.55f, 0.7f, 0.35f, 0.45f, 0.15f};

    static final class Voice {
        boolean active, released;
        int note, inst;
        int tag;            // 0 = played live, 1 = from the recorded track
        long offAt = -1;    // timeline sample where a track note ends
        long age;
        float vel, env, relMul;
        int t;              // samples since note on
        double freq;
        final double[] ph = new double[PARTIALS];
        final double[] inc = new double[PARTIALS];
        final float[] amp = new float[PARTIALS];
        final float[] decay = new float[PARTIALS];
        float lp1L, lp2L, lp1R, lp2R; // supersaw filter state
        float noise;
    }

    private final Voice[] voices = new Voice[MAX_VOICES];
    private final Random rnd = new Random(7);
    private long counter;
    private double lfo;

    public Synth() {
        for (int i = 0; i < MAX_VOICES; i++) voices[i] = new Voice();
    }

    public void noteOn(int note, int vel, int inst, int tag, long offAt) {
        Voice v = null;
        // same key already sounding from the same source: retrigger it
        for (Voice x : voices) if (x.active && x.note == note && x.tag == tag) { v = x; break; }
        if (v == null) for (Voice x : voices) if (!x.active) { v = x; break; }
        if (v == null) { // steal: oldest released voice, else oldest
            Voice best = null;
            for (Voice x : voices) if (x.released && (best == null || x.age < best.age)) best = x;
            if (best == null) for (Voice x : voices) if (best == null || x.age < best.age) best = x;
            v = best;
        }
        start(v, note, vel, inst, tag, offAt);
    }

    public void noteOff(int note, int tag) {
        for (Voice v : voices) if (v.active && !v.released && v.note == note && v.tag == tag) release(v);
    }

    public void releaseTag(int tag) {
        for (Voice v : voices) if (v.active && !v.released && v.tag == tag) release(v);
    }

    public void releaseAll() {
        for (Voice v : voices) if (v.active && !v.released) release(v);
    }

    /** Release track notes whose end falls before the given timeline position. */
    public void releaseEnded(long pos) {
        for (Voice v : voices)
            if (v.active && !v.released && v.tag == 1 && v.offAt >= 0 && v.offAt <= pos) release(v);
    }

    private void release(Voice v) {
        v.released = true;
        float sec;
        switch (v.inst) {
            case Project.INST_PIANO: sec = 0.18f; break;
            case Project.INST_SUPERSAW: sec = 0.25f; break;
            default: sec = 0.05f; break;
        }
        v.relMul = (float) Math.exp(-6.9 / (sec * SR)); // -60 dB over 'sec'
    }

    private void start(Voice v, int note, int vel, int inst, int tag, long offAt) {
        v.active = true; v.released = false;
        v.note = note; v.inst = inst; v.tag = tag; v.offAt = offAt;
        v.age = counter++;
        v.vel = Math.max(0.05f, vel / 127f);
        v.t = 0;
        v.freq = 440.0 * Math.pow(2, (note - 69) / 12.0);
        v.lp1L = v.lp2L = v.lp1R = v.lp2R = 0;
        v.noise = 0;
        if (inst == Project.INST_PIANO) {
            v.env = 1f;
            double B = 0.0004;
            double brightness = 0.6 + 0.4 * v.vel;
            for (int k = 0; k < PARTIALS; k++) {
                int h = k + 1;
                double f = v.freq * h * Math.sqrt(1 + B * h * h);
                if (f > 16000) { v.amp[k] = 0; v.inc[k] = 0; continue; }
                v.inc[k] = f / SR;
                v.ph[k] = 0;
                v.amp[k] = (float) (Math.pow(h, -1.25) * Math.pow(brightness, k * 0.6));
                double dec = (0.55 + 0.35 * h) * Math.sqrt(v.freq / 261.6);
                v.decay[k] = (float) Math.exp(-dec / SR);
            }
        } else if (inst == Project.INST_SUPERSAW) {
            v.env = 0f;
            for (int k = 0; k < SAW_DETUNE.length; k++) {
                v.inc[k] = v.freq * Math.pow(2, SAW_DETUNE[k] / 12.0) / SR;
                v.ph[k] = rnd.nextDouble();
            }
        } else {
            v.env = 0f;
            for (int k = 0; k < ORGAN_RATIO.length; k++) {
                double f = v.freq * ORGAN_RATIO[k];
                v.inc[k] = f < 15000 ? f / SR : 0;
                v.ph[k] = 0;
            }
        }
    }

    /** Adds the sound of all voices into the stereo buffer (interleaved L,R). */
    public void render(float[] out, int frames, float gain) {
        double lfoInc = 6.0 / SR;
        double lfoStart = lfo;
        for (Voice v : voices) {
            if (!v.active) continue;
            lfo = lfoStart;
            switch (v.inst) {
                case Project.INST_PIANO: renderPiano(v, out, frames, gain); break;
                case Project.INST_SUPERSAW: renderSaw(v, out, frames, gain); break;
                default: renderOrgan(v, out, frames, gain, lfoInc); break;
            }
        }
        lfo = lfoStart + lfoInc * frames;
        lfo -= Math.floor(lfo);
    }

    private void renderPiano(Voice v, float[] out, int n, float gain) {
        float g = gain * v.vel * 0.22f;
        float attack = SR * 0.002f;
        for (int i = 0; i < n; i++) {
            float s = 0;
            for (int k = 0; k < PARTIALS; k++) {
                if (v.inc[k] == 0) continue;
                s += sin(v.ph[k]) * v.amp[k];
                v.ph[k] += v.inc[k];
                if (v.ph[k] >= 1) v.ph[k] -= 1;
                v.amp[k] *= v.decay[k];
            }
            // short hammer thump at the start
            if (v.t < SR / 50) {
                float nz = rnd.nextFloat() * 2 - 1;
                v.noise += (nz - v.noise) * 0.25f;
                s += v.noise * 0.35f * (1f - v.t / (SR / 50f));
            }
            float a = v.t < attack ? v.t / attack : 1f;
            if (v.released) v.env *= v.relMul;
            float o = s * a * v.env * g;
            out[2 * i] += o;
            out[2 * i + 1] += o;
            v.t++;
        }
        if (v.env < 1e-4f || v.amp[0] < 1e-4f) v.active = false;
    }

    private void renderSaw(Voice v, float[] out, int n, float gain) {
        float g = gain * v.vel * 0.11f;
        float aRate = 1f / (SR * 0.006f);
        float dMul = (float) Math.exp(-1.0 / (SR * 0.35));
        float sustain = 0.7f;
        double cutoff = Math.min(16000, 1800 + v.freq * 7);
        float c = (float) (1 - Math.exp(-2 * Math.PI * cutoff / SR));
        for (int i = 0; i < n; i++) {
            float L = 0, R = 0;
            for (int k = 0; k < SAW_DETUNE.length; k++) {
                double p = v.ph[k], dt = v.inc[k];
                float s = (float) (2 * p - 1);
                // polyBLEP to soften aliasing
                if (p < dt) { double x = p / dt; s -= (float) (x + x - x * x - 1); }
                else if (p > 1 - dt) { double x = (p - 1) / dt; s -= (float) (x * x + x + x + 1); }
                p += dt;
                if (p >= 1) p -= 1;
                v.ph[k] = p;
                float pan = SAW_PAN[k];
                L += s * (1 - pan) * 0.5f;
                R += s * (1 + pan) * 0.5f;
            }
            v.lp1L += (L - v.lp1L) * c; v.lp2L += (v.lp1L - v.lp2L) * c;
            v.lp1R += (R - v.lp1R) * c; v.lp2R += (v.lp1R - v.lp2R) * c;
            if (v.released) v.env *= v.relMul;
            else if (v.t < SR * 0.006f) v.env = Math.min(1f, v.env + aRate);
            else v.env = sustain + (v.env - sustain) * dMul;
            out[2 * i] += v.lp2L * v.env * g;
            out[2 * i + 1] += v.lp2R * v.env * g;
            v.t++;
        }
        if (v.released && v.env < 1e-4f) v.active = false;
    }

    private void renderOrgan(Voice v, float[] out, int n, float gain, double lfoInc) {
        float g = gain * (0.5f + 0.5f * v.vel) * 0.075f;
        float aRate = 1f / (SR * 0.004f);
        for (int i = 0; i < n; i++) {
            double vib = 1 + 0.0025 * sin(lfo);
            float s = 0;
            for (int k = 0; k < ORGAN_RATIO.length; k++) {
                if (v.inc[k] == 0) continue;
                s += sin(v.ph[k]) * ORGAN_LEVEL[k];
                v.ph[k] += v.inc[k] * vib;
                if (v.ph[k] >= 1) v.ph[k] -= 1;
            }
            // little key click
            if (v.t < SR / 200) s += (rnd.nextFloat() * 2 - 1) * 0.3f * (1f - v.t / (SR / 200f));
            if (v.released) v.env *= v.relMul;
            else v.env = Math.min(1f, v.env + aRate);
            float rot = 0.15f * sin(lfo * 0.8 + 0.25);
            float o = s * v.env * g;
            out[2 * i] += o * (1 + rot);
            out[2 * i + 1] += o * (1 - rot);
            lfo += lfoInc;
            v.t++;
        }
        if (v.released && v.env < 1e-4f) v.active = false;
    }
}
