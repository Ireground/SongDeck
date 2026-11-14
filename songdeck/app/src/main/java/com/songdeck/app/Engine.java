package com.songdeck.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Process;

import java.util.concurrent.ConcurrentLinkedQueue;

/** Live audio output: runs the Mixer on a high-priority thread and plays it. */
public final class Engine {

    public static final int BLOCK = 256;

    public final Mixer mixer = new Mixer();
    private final ConcurrentLinkedQueue<Runnable> cmds = new ConcurrentLinkedQueue<>();
    private volatile boolean running;
    private Thread thread;
    private AudioTrack track;

    public synchronized void start() {
        if (running) return;
        int minBuf = AudioTrack.getMinBufferSize(Project.SR, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT);
        int bufBytes = Math.max(minBuf, BLOCK * 2 * 4 * 2);
        AudioTrack.Builder b = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(Project.SR)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build())
                .setBufferSizeInBytes(bufBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY);
        track = b.build();
        track.play();
        running = true;
        thread = new Thread(this::loop, "SongDeckAudio");
        thread.start();
    }

    public synchronized void shutdown() {
        running = false;
        if (thread != null) {
            try { thread.join(1000); } catch (InterruptedException ignored) { }
            thread = null;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }

    private void loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
        float[] buf = new float[BLOCK * 2];
        while (running) {
            Runnable r;
            while ((r = cmds.poll()) != null) {
                try { r.run(); } catch (Exception ignored) { }
            }
            mixer.render(buf, BLOCK, true);
            AudioTrack t = track;
            if (t == null) break;
            t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
        }
    }

    /** Runs a command on the audio thread before the next block. */
    public void post(Runnable r) {
        cmds.add(r);
        if (!running) { // nothing is draining the queue, run it now
            Runnable x;
            while ((x = cmds.poll()) != null) x.run();
        }
    }

    public void setState(Mixer.State s) { mixer.state = s; }

    public void liveNoteOn(int note, int vel) {
        post(() -> mixer.synth.noteOn(note, vel, mixer.state.instrument, 0, -1));
    }

    public void liveNoteOff(int note) {
        post(() -> mixer.synth.noteOff(note, 0));
    }
}
