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

    public List<Clip> clips = new ArrayList<>();
    public List<Note> notes = new ArrayList<>();

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

    public long beatLen() { return Math.round(SR * 60.0 / tempo); }

    /** End of the last clip or note. */
    public long contentEnd() {
        long e = 0;
        for (Clip c : clips) e = Math.max(e, c.end());
        for (Note n : notes) e = Math.max(e, n.end());
        return e;
    }

    public void sortNotes() {
        Collections.sort(notes, (a, b) -> Long.compare(a.start, b.start));
    }

    // ---------- JSON ----------

    public JSONObject editsToJson() throws JSONException {
        JSONObject o = new JSONObject();
        JSONArray ca = new JSONArray();
        for (Clip c : clips) {
            JSONObject j = new JSONObject();
            j.put("src", c.src); j.put("srcStart", c.srcStart); j.put("len", c.len);
            j.put("start", c.start); j.put("cents", c.cents);
            ca.put(j);
        }
        JSONArray na = new JSONArray();
        for (Note n : notes) {
            JSONObject j = new JSONObject();
            j.put("p", n.pitch); j.put("v", n.vel); j.put("s", n.start); j.put("l", n.len);
            na.put(j);
        }
        o.put("clips", ca);
        o.put("notes", na);
        return o;
    }

    public void editsFromJson(JSONObject o) throws JSONException {
        clips = new ArrayList<>();
        notes = new ArrayList<>();
        JSONArray ca = o.optJSONArray("clips");
        if (ca != null) for (int i = 0; i < ca.length(); i++) {
            JSONObject j = ca.getJSONObject(i);
            Clip c = new Clip();
            c.src = j.getString("src"); c.srcStart = j.getInt("srcStart"); c.len = j.getInt("len");
            c.start = j.getLong("start"); c.cents = j.optInt("cents", 0);
            clips.add(c);
        }
        JSONArray na = o.optJSONArray("notes");
        if (na != null) for (int i = 0; i < na.length(); i++) {
            JSONObject j = na.getJSONObject(i);
            Note n = new Note();
            n.pitch = j.getInt("p"); n.vel = j.optInt("v", 100); n.start = j.getLong("s"); n.len = j.getLong("l");
            notes.add(n);
        }
        sortNotes();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = editsToJson();
        o.put("id", id); o.put("name", name); o.put("folderId", folderId);
        o.put("created", created); o.put("modified", modified);
        o.put("tempo", tempo); o.put("metronome", metronome); o.put("instrument", instrument);
        o.put("octave", octave); o.put("vocalVol", vocalVol); o.put("keysVol", keysVol);
        o.put("vocalMute", vocalMute); o.put("keysMute", keysMute);
        o.put("vocalArm", vocalArm); o.put("keysArm", keysArm); o.put("cursor", cursor);
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
        p.editsFromJson(o);
        return p;
    }
}
