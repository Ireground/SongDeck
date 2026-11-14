package com.songdeck.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One song idea: settings, vocal clips and keyboard notes. All times are in samples (48 kHz). */
public class Project {

    public static final int SR = 48000;

    public static final int INST_PIANO = 0, INST_SUPERSAW = 1, INST_ORGAN = 2;
    public static final String[] INST_NAMES = {"PIANO", "SUPERSAW", "ORGAN"};

    public String id = "";
    public String name = "Idea";
    public String folderId = "";
    public long created, modified;

    public int tempo = 120;
    public boolean metronome = true;
    public int instrument = INST_PIANO;
    public int octave = 3;              // lowest key on screen is C of this octave (C3 = MIDI 48)
    public float vocalVol = 1f, keysVol = 0.8f;
    public boolean vocalMute, keysMute;
    public boolean vocalArm = true, keysArm = true;
    public long cursor;                 // where playback starts

    // one-finger chords
    public boolean chordMode;
    public int keyOctaves = 2;          // keyboard size on screen: 1 or 2 octaves
    public int chordKey = 0, chordScale = 0, chordShape = 0, chordNotes = 3, chordInv = 0;

    public List<Clip> clips = new ArrayList<>();
    public List<MidiClip> midiClips = new ArrayList<>();

    public static class Clip {
        public String src;   // name of the recorded audio file
        public int srcStart; // first sample used from the file
        public int len;      // number of samples used
        public long start;   // position on the timeline
        public int cents;    // pitch, -100..100

        public Clip copy() {
            Clip c = new Clip();
            c.src = src; c.srcStart = srcStart; c.len = len; c.start = start; c.cents = cents;
            return c;
        }
        public long end() { return start + len; }
        public String cacheKey() { return src + ":" + srcStart + ":" + len + ":" + cents; }
    }

    public static class Note {
        public int pitch;    // MIDI note number
        public int vel;      // 1..127
        public long start, len;

        public Note copy() {
            Note n = new Note();
            n.pitch = pitch; n.vel = vel; n.start = start; n.len = len;
            return n;
        }
        public long end() { return start + len; }
    }

    /**
     * A clip on the keyboard track. Note times are relative to the clip's content;
     * 'offset' is how much of the content is trimmed off the left edge.
     */
    public static class MidiClip {
        public long start, len, offset;
        public List<Note> notes = new ArrayList<>();

        public long end() { return start + len; }
        /** Timeline position of content time 0. */
        public long origin() { return start - offset; }

        public MidiClip copy() {
            MidiClip m = new MidiClip();
            m.start = start; m.len = len; m.offset = offset;
            for (Note n : notes) m.notes.add(n.copy());
            return m;
        }

        /** Notes as they sound on the timeline: cut at the clip edges. */
        public void addAudible(List<Note> out) {
            long o = origin(), e = end();
            for (Note n : notes) {
                long s = o + n.start;
                if (s < start || s >= e) continue;
                Note x = new Note();
                x.pitch = n.pitch; x.vel = n.vel; x.start = s;
                x.len = Math.max(1, Math.min(o + n.end(), e) - s);
                out.add(x);
            }
        }
    }

    public long beatLen() { return Math.round(SR * 60.0 / tempo); }
    public long barLen() { return beatLen() * 4; }

    /** Every keyboard note that will sound, sorted by time. */
    public List<Note> flatNotes() {
        List<Note> out = new ArrayList<>();
        for (MidiClip m : midiClips) m.addAudible(out);
        Collections.sort(out, (a, b) -> Long.compare(a.start, b.start));
        return out;
    }

    public int noteCount() {
        int n = 0;
        for (MidiClip m : midiClips) n += m.notes.size();
        return n;
    }

    /** End of the last clip. */
    public long contentEnd() {
        long e = 0;
        for (Clip c : clips) e = Math.max(e, c.end());
        for (MidiClip m : midiClips) e = Math.max(e, m.end());
        return e;
    }

    /** Wraps loose notes (older projects) into one clip that covers whole bars. */
    public static MidiClip clipFromNotes(List<Note> abs, long bar) {
        MidiClip m = new MidiClip();
        long first = Long.MAX_VALUE, last = 0;
        for (Note n : abs) { first = Math.min(first, n.start); last = Math.max(last, n.end()); }
        long s = (first / bar) * bar;
        long e = Math.max(s + bar, ((last + bar - 1) / bar) * bar);
        m.start = s; m.len = e - s; m.offset = 0;
        for (Note n : abs) { Note x = n.copy(); x.start -= s; m.notes.add(x); }
        return m;
    }

    // ---------- JSON ----------

    private static JSONArray notesToJson(List<Note> list) throws JSONException {
        JSONArray na = new JSONArray();
        for (Note n : list) {
            JSONObject j = new JSONObject();
            j.put("p", n.pitch); j.put("v", n.vel); j.put("s", n.start); j.put("l", n.len);
            na.put(j);
        }
        return na;
    }

    private static List<Note> notesFromJson(JSONArray na) throws JSONException {
        List<Note> out = new ArrayList<>();
        if (na != null) for (int i = 0; i < na.length(); i++) {
            JSONObject j = na.getJSONObject(i);
            Note n = new Note();
            n.pitch = j.getInt("p"); n.vel = j.optInt("v", 100); n.start = j.getLong("s"); n.len = j.getLong("l");
            out.add(n);
        }
        return out;
    }

    public JSONObject editsToJson() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray ca = new JSONArray();
        for (Clip c : clips) {
            JSONObject j = new JSONObject();
            j.put("src", c.src); j.put("srcStart", c.srcStart); j.put("len", c.len);
            j.put("start", c.start); j.put("cents", c.cents);
            ca.put(j);
        }
        JSONArray ma = new JSONArray();
        for (MidiClip m : midiClips) {
            JSONObject j = new JSONObject();
            j.put("start", m.start); j.put("len", m.len); j.put("offset", m.offset);
            j.put("notes", notesToJson(m.notes));
            ma.put(j);
        }
        o.put("clips", ca);
        o.put("midiClips", ma);
        return o;
    }

    public void editsFromJson(JSONObject o) throws JSONException {
        clips = new ArrayList<>();
        midiClips = new ArrayList<>();
        JSONArray ca = o.optJSONArray("clips");
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            JSONObject j = ca.getJSONObject(i);
            Clip c = new Clip();
            c.src = j.getString("src"); c.srcStart = j.getInt("srcStart"); c.len = j.getInt("len");
            c.start = j.getLong("start"); c.cents = j.optInt("cents", 0);
            clips.add(c);
        }
        JSONArray ma = o.optJSONArray("midiClips");
        if (ma != null) for (int i = 0; i < ma.length(); i++) {
            JSONObject j = ma.getJSONObject(i);
            MidiClip m = new MidiClip();
            m.start = j.getLong("start"); m.len = j.getLong("len"); m.offset = j.optLong("offset", 0);
            m.notes = notesFromJson(j.optJSONArray("notes"));
            midiClips.add(m);
        }
        // older projects stored loose notes: turn them into one clip
        List<Note> loose = notesFromJson(o.optJSONArray("notes"));
        if (!loose.isEmpty()) midiClips.add(clipFromNotes(loose, barLen()));
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = editsToJson();
        o.put("id", id); o.put("name", name); o.put("folderId", folderId);
        o.put("created", created); o.put("modified", modified);
        o.put("tempo", tempo); o.put("metronome", metronome); o.put("instrument", instrument);
        o.put("octave", octave); o.put("vocalVol", vocalVol); o.put("keysVol", keysVol);
        o.put("vocalMute", vocalMute); o.put("keysMute", keysMute);
        o.put("vocalArm", vocalArm); o.put("keysArm", keysArm); o.put("cursor", cursor);
        o.put("chordMode", chordMode); o.put("keyOctaves", keyOctaves); o.put("chordKey", chordKey); o.put("chordScale", chordScale);
        o.put("chordShape", chordShape); o.put("chordNotes", chordNotes); o.put("chordInv", chordInv);
        return o;
    }

    public static Project fromJson(JSONObject o) throws JSONException {
        Project p = new Project();
        p.id = o.optString("id", ""); p.name = o.optString("name", "Idea");
        p.folderId = o.optString("folderId", "");
        p.created = o.optLong("created", 0); p.modified = o.optLong("modified", 0);
        p.tempo = o.optInt("tempo", 120); p.metronome = o.optBoolean("metronome", true);
        p.instrument = o.optInt("instrument", 0); p.octave = o.optInt("octave", 3);
        p.vocalVol = (float) o.optDouble("vocalVol", 1.0); p.keysVol = (float) o.optDouble("keysVol", 0.8);
        p.vocalMute = o.optBoolean("vocalMute", false); p.keysMute = o.optBoolean("keysMute", false);
        p.vocalArm = o.optBoolean("vocalArm", true); p.keysArm = o.optBoolean("keysArm", true);
        p.cursor = o.optLong("cursor", 0);
        p.chordMode = o.optBoolean("chordMode", false);
        p.keyOctaves = o.optInt("keyOctaves", 2);
        p.chordKey = o.optInt("chordKey", 0); p.chordScale = o.optInt("chordScale", 0);
        p.chordShape = o.optInt("chordShape", 0); p.chordNotes = o.optInt("chordNotes", 3);
        p.chordInv = o.optInt("chordInv", 0);
        p.editsFromJson(o);
        return p;
    }
}
