package com.songdeck.app;

/**
 * Produces the song's sound, block by block: vocal clips, keyboard notes, metronome.
 * Used by the live audio engine and by the exporter. Only one thread may call it.
 */
public final class Mixer {

    public static final int SR = Project.SR;

    public static final class ClipPlay {
        public short[] data;
        public float[] shifted; // pitch-shifted version (null = use original)
        public int srcStart, len;
        public long start;
    }

    public static final class NotePlay {
        public int pitch, vel;
        public long start, end;
    }

    public static final class State {
        public ClipPlay[] clips = new ClipPlay[0];
        public NotePlay[] notes = new NotePlay[0]; // sorted by start
        public float vocalGain = 1f, keysGain = 0.8f;
        public boolean metronome = true;
        public int tempo = 120;
        public int instrument = 0;
    }

    /** Something that wants to know the exact moment the transport starts (the mic recorder). */
    public interface StartListener { void onTransportStart(long pos); }

    public volatile State state = new State();
    public final Synth synth = new Synth();

    public volatile long pos;
    public volatile boolean playing;
    public volatile boolean counting;
    long countInLeft;
    public volatile long countInPos;
    boolean waitingForStart;
    StartListener startListener;
    private int noteIdx;
    private State lastState;

    private static final int FADE = 96;

    // ---------- transport (call from the audio thread) ----------

    public void play(long from) {
        synth.releaseTag(1);
        pos = from;
        startTransport();
    }

    public void stop() {
        playing = false;
        counting = false;
        waitingForStart = false;
        countInLeft = 0;
        startListener = null;
        synth.releaseTag(1);
    }

    public void seek(long p) {
        pos = Math.max(0, p);
        synth.releaseTag(1);
        noteIdx = findNote(pos);
    }

    /** Starts after a count-in of the given number of beats (0 = wait only for the listener). */
    public void startWithCountIn(long from, int beats, StartListener l, boolean waitForSignal) {
        synth.releaseTag(1);
        pos = from;
        startListener = l;
        playing = false;
        long beat = Math.round(SR * 60.0 / state.tempo);
        countInLeft = beats * beat;
        countInPos = 0;
        counting = beats > 0;
        waitingForStart = waitForSignal;
    }

    /** Called when the microphone has delivered its first audio. */
    public void signalReady() {
        if (!waitingForStart) return;
        waitingForStart = false;
        if (!counting) startTransport();
    }

    private void startTransport() {
        counting = false;
        playing = true;
        noteIdx = findNote(pos);
        if (startListener != null) startListener.onTransportStart(pos);
    }

    private int findNote(long p) {
        NotePlay[] ns = state.notes;
        int i = 0;
        while (i < ns.length && ns[i].start < p) i++;
        return i;
    }

    // ---------- rendering ----------

    /** Renders interleaved stereo frames into out (overwrites). */
    public void render(float[] out, int frames, boolean withMetronome) {
        for (int i = 0; i < frames * 2; i++) out[i] = 0f;
        State s = state;
        if (s != lastState) { lastState = s; noteIdx = findNote(pos); }

        if (counting || waitingForStart) {
            if (counting) {
                long beat = Math.round(SR * 60.0 / s.tempo);
                clicks(out, frames, countInPos, beat, 0);
                countInPos += frames;
                countInLeft -= frames;
                if (countInLeft <= 0) counting = false;
            }
            if (!counting && !waitingForStart) startTransport();
        } else if (playing) {
            long p0 = pos, p1 = pos + frames;
            // keyboard notes
            NotePlay[] ns = s.notes;
            if (noteIdx > ns.length) noteIdx = ns.length;
            while (noteIdx < ns.length && ns[noteIdx].start < p1) {
                NotePlay n = ns[noteIdx++];
                if (n.start >= p0 - frames) synth.noteOn(n.pitch, n.vel, s.instrument, 1, n.end);
            }
            synth.releaseEnded(p1);
            // vocal clips
            if (s.vocalGain > 0) for (ClipPlay c : s.clips) mixClip(c, out, p0, frames, s.vocalGain);
            if (withMetronome && s.metronome) {
                long beat = Math.round(SR * 60.0 / s.tempo);
                clicks(out, frames, p0, beat, 0);
            }
            pos = p1;
        }

        synth.render(out, frames, s.keysGain);

        // gentle safety limiter (soft clip)
        for (int i = 0; i < frames * 2; i++) {
            float x = out[i];
            if (x > 1.5f) x = 1.5f; else if (x < -1.5f) x = -1.5f;
            out[i] = x * (27 + x * x) / (27 + 9 * x * x);
        }
    }

    private static void mixClip(ClipPlay c, float[] out, long p0, int frames, float gain) {
        long cs = c.start, ce = c.start + c.len;
        if (ce <= p0 || cs >= p0 + frames) return;
        int from = (int) Math.max(0, cs - p0);
        int to = (int) Math.min(frames, ce - p0);
        for (int i = from; i < to; i++) {
            int k = (int) (p0 + i - cs);
            float v;
            if (c.shifted != null) v = k < c.shifted.length ? c.shifted[k] : 0f;
            else {
                int si = c.srcStart + k;
                v = si < c.data.length ? c.data[si] / 32768f : 0f;
            }
            if (k < FADE) v *= k / (float) FADE;
            int fromEnd = c.len - 1 - k;
            if (fromEnd < FADE) v *= fromEnd / (float) FADE;
            v *= gain;
            out[2 * i] += v;
            out[2 * i + 1] += v;
        }
    }

    /** Metronome clicks for timeline samples [t0, t0+frames). */
    private static void clicks(float[] out, int frames, long t0, long beat, int dummy) {
        int clickLen = SR / 25;
        for (int i = 0; i < frames; i++) {
            long t = t0 + i;
            long b = t / beat;
            long inBeat = t - b * beat;
            if (inBeat >= clickLen) continue;
            boolean down = (b % 4) == 0;
            double f = down ? 1760 : 1175;
            double tt = inBeat / (double) SR;
            float v = (float) (Math.sin(2 * Math.PI * f * tt) * Math.exp(-tt * 90)) * (down ? 0.55f : 0.4f);
            out[2 * i] += v;
            out[2 * i + 1] += v;
        }
    }
}
