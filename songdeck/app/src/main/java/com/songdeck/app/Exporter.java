package com.songdeck.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Renders the song to a WAV file, and writes the keyboard track as a MIDI file. */
public final class Exporter {

    private static final int SR = Project.SR;

    /** Length of the rendered audio: content plus a short tail for the sound to ring out. */
    public static long renderLength(Project p) {
        return p.contentEnd() + SR * 2L;
    }

    public static void writeWav(Mixer.State state, long frames, OutputStream os) throws IOException {
        Mixer m = new Mixer();
        m.state = state;
        m.play(0);
        int dataBytes = (int) (frames * 4);
        byte[] h = new byte[44];
        putStr(h, 0, "RIFF"); putInt(h, 4, 36 + dataBytes); putStr(h, 8, "WAVE");
        putStr(h, 12, "fmt "); putInt(h, 16, 16); putShort(h, 20, 1); putShort(h, 22, 2);
        putInt(h, 24, SR); putInt(h, 28, SR * 4); putShort(h, 32, 4); putShort(h, 34, 16);
        putStr(h, 36, "data"); putInt(h, 40, dataBytes);
        os.write(h);
        final int B = 1024;
        float[] buf = new float[B * 2];
        byte[] out = new byte[B * 4];
        long done = 0;
        while (done < frames) {
            int n = (int) Math.min(B, frames - done);
            m.render(buf, n, false);
            for (int i = 0; i < n * 2; i++) {
                float x = Math.max(-1f, Math.min(1f, buf[i]));
                int v = Math.round(x * 32767f);
                out[2 * i] = (byte) v;
                out[2 * i + 1] = (byte) (v >> 8);
            }
            os.write(out, 0, n * 4);
            done += n;
        }
        os.flush();
    }

    // ---------- MIDI ----------

    private static final int PPQ = 480;

    public static void writeMidi(Project p, OutputStream os) throws IOException {
        double ticksPerSample = (p.tempo / 60.0) * PPQ / SR;
        List<long[]> ev = new ArrayList<>(); // {tick, order, status, data1, data2}
        for (Project.Note n : p.flatNotes()) {
            long on = Math.round(n.start * ticksPerSample);
            long off = Math.max(on + 1, Math.round(n.end() * ticksPerSample));
            int pitch = Math.max(0, Math.min(127, n.pitch));
            ev.add(new long[]{on, 1, 0x90, pitch, Math.max(1, Math.min(127, n.vel))});
            ev.add(new long[]{off, 0, 0x80, pitch, 0});
        }
        Collections.sort(ev, (a, b) -> a[0] != b[0] ? Long.compare(a[0], b[0]) : Long.compare(a[1], b[1]));

        ByteArrayOutputStream t = new ByteArrayOutputStream();
        // track name
        byte[] name = (p.name + " - Keys").getBytes("UTF-8");
        vlq(t, 0); t.write(0xFF); t.write(0x03); vlq(t, name.length); t.write(name);
        // tempo
        int usPerBeat = (int) Math.round(60000000.0 / p.tempo);
        vlq(t, 0); t.write(0xFF); t.write(0x51); t.write(3);
        t.write((usPerBeat >> 16) & 0xFF); t.write((usPerBeat >> 8) & 0xFF); t.write(usPerBeat & 0xFF);
        // 4/4
        vlq(t, 0); t.write(0xFF); t.write(0x58); t.write(4); t.write(4); t.write(2); t.write(24); t.write(8);
        // instrument (General MIDI program)
        int program = p.instrument == Project.INST_PIANO ? 0 : p.instrument == Project.INST_SUPERSAW ? 81 : 16;
        vlq(t, 0); t.write(0xC0); t.write(program);
        long last = 0;
        for (long[] e : ev) {
            vlq(t, e[0] - last);
            last = e[0];
            t.write((int) e[2]); t.write((int) e[3]); t.write((int) e[4]);
        }
        vlq(t, 0); t.write(0xFF); t.write(0x2F); t.write(0);
        byte[] track = t.toByteArray();

        byte[] h = new byte[14];
        putStr(h, 0, "MThd"); putIntBE(h, 4, 6); putShortBE(h, 8, 0); putShortBE(h, 10, 1); putShortBE(h, 12, PPQ);
        os.write(h);
        byte[] th = new byte[8];
        putStr(th, 0, "MTrk"); putIntBE(th, 4, track.length);
        os.write(th);
        os.write(track);
        os.flush();
    }

    private static void vlq(ByteArrayOutputStream o, long v) {
        if (v < 0) v = 0;
        long buffer = v & 0x7F;
        while ((v >>= 7) > 0) {
            buffer <<= 8;
            buffer |= ((v & 0x7F) | 0x80);
        }
        while (true) {
            o.write((int) (buffer & 0xFF));
            if ((buffer & 0x80) != 0) buffer >>= 8;
            else break;
        }
    }

    private static void putStr(byte[] b, int o, String s) { for (int i = 0; i < 4; i++) b[o + i] = (byte) s.charAt(i); }
    private static void putInt(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * i)); }
    private static void putShort(byte[] b, int o, int v) { b[o] = (byte) v; b[o + 1] = (byte) (v >> 8); }
    private static void putIntBE(byte[] b, int o, int v) { for (int i = 0; i < 4; i++) b[o + i] = (byte) (v >> (8 * (3 - i))); }
    private static void putShortBE(byte[] b, int o, int v) { b[o] = (byte) (v >> 8); b[o + 1] = (byte) v; }
}
