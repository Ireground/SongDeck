package com.songdeck.app;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.ArrayList;
import java.util.List;

/** Records the microphone (mono, 16-bit, 48 kHz) on its own thread. */
public final class Recorder implements Mixer.StartListener {

    private static final int CHUNK = 960; // 20 ms

    private final List<short[]> chunks = new ArrayList<>();
    private volatile int count;            // samples recorded so far
    public volatile long markCount = -1;   // samples recorded when the song started moving
    public volatile long markPos;          // timeline position at that moment
    private volatile boolean running;
    private Thread thread;
    private AudioRecord rec;
    private float level;

    @SuppressLint("MissingPermission")
    public boolean start(AudioManager am, Runnable onFirstData) {
        int min = AudioRecord.getMinBufferSize(Project.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        int bufSize = Math.max(min * 2, CHUNK * 2 * 8);
        int source = MediaRecorder.AudioSource.MIC;
        try {
            String unp = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
            if ("true".equals(unp)) source = MediaRecorder.AudioSource.UNPROCESSED;
        } catch (Exception ignored) { }
        try {
            rec = new AudioRecord(source, Project.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) {
                rec.release();
                rec = new AudioRecord(MediaRecorder.AudioSource.MIC, Project.SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            }
            if (rec.getState() != AudioRecord.STATE_INITIALIZED) { rec.release(); rec = null; return false; }
            rec.startRecording();
        } catch (Exception e) {
            if (rec != null) rec.release();
            rec = null;
            return false;
        }
        running = true;
        thread = new Thread(() -> {
            boolean first = true;
            while (running) {
                short[] b = new short[CHUNK];
                int n = rec.read(b, 0, CHUNK);
                if (n <= 0) { if (n < 0) break; continue; }
                if (n < CHUNK) {
                    short[] c = new short[n];
                    System.arraycopy(b, 0, c, 0, n);
                    b = c;
                }
                float peak = 0;
                for (short s : b) peak = Math.max(peak, Math.abs(s) / 32768f);
                level = Math.max(peak, level * 0.8f);
                synchronized (chunks) { chunks.add(b); }
                count += b.length;
                if (first) { first = false; if (onFirstData != null) onFirstData.run(); }
            }
        }, "SongDeckMic");
        thread.start();
        return true;
    }

    @Override
    public void onTransportStart(long pos) {
        markPos = pos;
        markCount = count;
    }

    public float level() { return level; }

    /** Stops and returns everything recorded. */
    public short[] stop() {
        running = false;
        if (thread != null) {
            try { thread.join(1500); } catch (InterruptedException ignored) { }
        }
        if (rec != null) {
            try { rec.stop(); } catch (Exception ignored) { }
            rec.release();
            rec = null;
        }
        short[] all;
        synchronized (chunks) {
            int total = 0;
            for (short[] c : chunks) total += c.length;
            all = new short[total];
            int o = 0;
            for (short[] c : chunks) { System.arraycopy(c, 0, all, o, c.length); o += c.length; }
        }
        return all;
    }
}
