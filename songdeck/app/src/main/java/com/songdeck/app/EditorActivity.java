package com.songdeck.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The recording screen from the mock-up. */
public class EditorActivity extends Activity implements TimelineView.Host, KeyboardView.Listener {

    private static final int SR = Project.SR;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int REQ_MIC = 1, REQ_EXPORT = 10;

    private Store store;
    private Project project;
    private final Engine engine = new Engine();
    private Recorder recorder;
    private boolean recording;
    private long recordStart;

    private final Map<String, short[]> audio = new HashMap<>();
    private final Map<String, float[]> peaks = new HashMap<>();
    private final Map<String, float[]> pitchCache = new HashMap<>();
    private final Set<String> pitchPending = new HashSet<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayDeque<String> undo = new ArrayDeque<>();
    private final Map<Integer, long[]> heldNotes = new HashMap<>();
    private Object selected;                         // a Project.Clip or a Project.MidiClip
    private final List<Project.Note> recNotes = new ArrayList<>(); // keyboard notes of the take being recorded

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TimelineView timeline;
    private KeyboardView keyboard;
    private TextView nameView, statusPill, metroBtn, tempoBtn, octaveLabel, recBtn, preBtn, playBtn;
    private TextView[] instBtns = new TextView[3];
    private TextView dupBtn, delBtn, pitchBtn;

    // ------------------------------------------------------------ setup

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.init(this);
        store = new Store(this);
        project = store.loadProject(getIntent().getStringExtra("id"));
        if (project == null) { finish(); return; }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Ui.BG);
        getWindow().setNavigationBarColor(Ui.BG);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);

        for (Project.Clip c : project.clips) loadSource(c.src);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.BG);
        root.setPadding(Ui.dp(8), Ui.dp(6), Ui.dp(8), Ui.dp(6));

        root.addView(buildToolbar(), new LinearLayout.LayoutParams(MATCH, WRAP));

        timeline = new TimelineView(this);
        timeline.setHost(this);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        tl.topMargin = Ui.dp(6);
        root.addView(timeline, tl);

        keyboard = new KeyboardView(this);
        LinearLayout.LayoutParams kb = new LinearLayout.LayoutParams(MATCH, WRAP);
        kb.topMargin = Ui.dp(6);
        root.addView(buildKeysBar(), kb);

        keyboard.setListener(this);
        keyboard.setBaseNote(12 * (project.octave + 1));
        keyboard.setOctaves(project.keyOctaves);
        LinearLayout.LayoutParams kl = new LinearLayout.LayoutParams(MATCH, 0, 1f);
        kl.topMargin = Ui.dp(6);
        root.addView(keyboard, kl);

        setContentView(root);
        refreshButtons();
        refreshSelection();
        refreshChordUi();

        for (Project.Clip c : project.clips) if (c.cents != 0) schedulePitch(c);
        pushState();
        engine.post(() -> engine.mixer.seek(project.cursor));

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private View buildToolbar() {
        Ui.Flow bar = new Ui.Flow(this, 6);

        bar.addView(Ui.pill(this, "◀ Ideas", Ui.GREY, v -> finish()));
        nameView = Ui.label(this, project.name, 16, Ui.TEXT, true);
        nameView.setSingleLine(true);
        nameView.setEllipsize(TextUtils.TruncateAt.END);
        nameView.setMaxWidth(Ui.dp(170));
        nameView.setPadding(Ui.dp(6), Ui.dp(6), Ui.dp(10), Ui.dp(6));
        nameView.setOnClickListener(v -> renameProject());
        bar.addView(nameView);

        bar.addView(big(Ui.pill(this, "⏮", Ui.GREY, v -> backToStart())));
        playBtn = big(Ui.pill(this, "▶", Ui.GREY, v -> play()));
        bar.addView(playBtn);
        bar.addView(big(Ui.pill(this, "■", Ui.GREY, v -> stop())));
        recBtn = big(Ui.pill(this, "●", 0xFF9B1C22, v -> record(false)));
        bar.addView(recBtn);
        preBtn = big(Ui.pill(this, "", 0xFF9B1C22, v -> record(true)));
        SpannableString pre = new SpannableString("●\n• • • •");
        pre.setSpan(new RelativeSizeSpan(0.45f), 2, pre.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        preBtn.setText(pre);
        preBtn.setLineSpacing(0, 0.8f);
        bar.addView(preBtn);

        metroBtn = big(Ui.pill(this, "", Ui.GREY, v -> {
            project.metronome = !project.metronome;
            refreshButtons();
            pushState();
        }));
        metroBtn.setTextSize(14);
        bar.addView(metroBtn);
        tempoBtn = big(Ui.pill(this, "", Ui.PANEL2, v -> tempoDialog()));
        tempoBtn.setTextSize(14);
        bar.addView(tempoBtn);
        bar.addView(Ui.pill(this, "Split at cursor", Ui.GREEN, v -> split()));
        bar.addView(Ui.pill(this, "↶ Undo", Ui.GREY, v -> undo()));
        dupBtn = Ui.pill(this, "Duplicate", Ui.GREY, v -> duplicate());
        bar.addView(dupBtn);
        pitchBtn = Ui.pill(this, "Pitch", Ui.GREY, v -> { if (selected instanceof Project.Clip) pitchDialog((Project.Clip) selected); });
        bar.addView(pitchBtn);
        delBtn = Ui.pill(this, "Delete", Ui.GREY, v -> deleteSelected());
        bar.addView(delBtn);

        statusPill = Ui.label(this, "", 11, 0xFF000000, true);
        statusPill.setPadding(Ui.dp(12), Ui.dp(5), Ui.dp(12), Ui.dp(5));
        bar.addView(statusPill);
        bar.addView(Ui.pill(this, "Save", Ui.GREEN, v -> { save(); toast("Saved"); }));
        bar.addView(Ui.pill(this, "Export", Ui.GREEN, v -> exportMenu()));
        bar.addView(Ui.pill(this, "Share", Ui.BLUE, v -> shareMenu()));
        bar.addView(Ui.pill(this, "⚙", Ui.GREY, v -> Settings.show(this, store, null)));
        return bar;
    }

    private TextView big(TextView t) {
        t.setTextSize(20);
        t.setMinWidth(Ui.dp(58));
        t.setPadding(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4));
        t.setMinHeight(Ui.dp(48));
        return t;
    }

    private void add(LinearLayout bar, View v, int leftMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = Ui.dp(leftMarginDp);
        bar.addView(v, lp);
    }

    /** Row above the keyboard: sounds, octave, quantize and the one-finger chord controls. */
    private View buildKeysBar() {
        Ui.Flow bar = new Ui.Flow(this, 6);
        for (int i = 0; i < 3; i++) {
            final int inst = i;
            instBtns[i] = Ui.pill(this, Project.INST_NAMES[i], Ui.GREY, v -> {
                project.instrument = inst;
                refreshButtons();
                pushState();
                timeline.invalidate();
            });
            bar.addView(instBtns[i]);
        }
        LinearLayout oct = new LinearLayout(this);
        oct.setGravity(Gravity.CENTER_VERTICAL);
        oct.addView(Ui.pill(this, "⬅", Ui.BLUE, v -> shiftOctave(-1)));
        octaveLabel = Ui.label(this, "", 13, Ui.TEXT, true);
        octaveLabel.setGravity(Gravity.CENTER);
        octaveLabel.setMinWidth(Ui.dp(56));
        oct.addView(octaveLabel);
        oct.addView(Ui.pill(this, "➡", Ui.BLUE, v -> shiftOctave(1)));
        bar.addView(oct);
        sizeToggle = Ui.pill(this, "", Ui.GREY, v -> {
            project.keyOctaves = project.keyOctaves == 1 ? 2 : 1;
            keyboard.setOctaves(project.keyOctaves);
            refreshButtons();
        });
        bar.addView(sizeToggle);
        bar.addView(Ui.pill(this, "Quantize", Ui.GREY, v -> quantize()));

        chordToggle = Ui.pill(this, "", Ui.GREY, v -> {
            keyboard.releaseAll();
            project.chordMode = !project.chordMode;
            refreshChordUi();
        });
        bar.addView(chordToggle);

        chordViews.clear();
        chordViews.add(stepper(bar, "KEY", () -> chordKeyText(), d -> project.chordKey = (project.chordKey + d + 12) % 12,
                () -> pickFrom("Key", ChordMaker.KEYS, project.chordKey, i -> project.chordKey = i)));
        chordViews.add(stepper(bar, "SCALE", () -> ChordMaker.SCALE_NAMES[project.chordScale],
                d -> project.chordScale = (project.chordScale + d + 9) % 9,
                () -> pickFrom("Scale", ChordMaker.SCALE_NAMES, project.chordScale, i -> project.chordScale = i)));
        chordViews.add(stepper(bar, "SHAPE", () -> ChordMaker.SHAPE_NAMES[project.chordShape],
                d -> project.chordShape = (project.chordShape + d + 6) % 6,
                () -> pickFrom("Chord shape", ChordMaker.SHAPE_NAMES, project.chordShape, i -> project.chordShape = i)));
        chordViews.add(stepper(bar, "NOTES", () -> String.valueOf(project.chordNotes), d -> {
            project.chordNotes = Math.max(1, Math.min(6, project.chordNotes + d));
            project.chordInv = Math.min(project.chordInv, project.chordNotes - 1);
        }, null));
        chordViews.add(stepper(bar, "INVERSION", () -> String.valueOf(project.chordInv),
                d -> project.chordInv = Math.max(0, Math.min(project.chordNotes - 1, project.chordInv + d)), null));
        chordName = Ui.label(this, "", 18, Ui.BLUE, true);
        chordName.setMinWidth(Ui.dp(90));
        chordName.setGravity(Gravity.CENTER);
        chordName.setPadding(Ui.dp(10), Ui.dp(4), Ui.dp(10), Ui.dp(4));
        bar.addView(chordName);
        chordViews.add(chordName);
        return bar;
    }

    private final List<View> chordViews = new ArrayList<>();
    private final List<Runnable> chordRefreshers = new ArrayList<>();
    private TextView chordToggle, chordName, sizeToggle;

    private String chordKeyText() { return ChordMaker.KEYS[project.chordKey]; }

    /** A small "LABEL ‹ value ›" control. Tapping the value opens the full list when there is one. */
    private View stepper(Ui.Flow bar, String label, java.util.function.Supplier<String> value,
                         java.util.function.IntConsumer step, Runnable openList) {
        LinearLayout box = new LinearLayout(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackground(Ui.round(Ui.PANEL, 10));
        box.setPadding(Ui.dp(10), Ui.dp(2), Ui.dp(4), Ui.dp(2));
        box.addView(Ui.label(this, label, 10, Ui.DIM, true));
        TextView minus = Ui.pill(this, "‹", Ui.GREY, v -> { step.accept(-1); refreshChordUi(); });
        minus.setMinWidth(Ui.dp(40));
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(WRAP, WRAP);
        mp.leftMargin = Ui.dp(8);
        box.addView(minus, mp);
        TextView val = Ui.label(this, "", 14, Ui.TEXT, true);
        val.setGravity(Gravity.CENTER);
        val.setMinWidth(Ui.dp(label.equals("SCALE") ? 130 : label.equals("SHAPE") ? 105 : 34));
        val.setPadding(Ui.dp(6), Ui.dp(8), Ui.dp(6), Ui.dp(8));
        if (openList != null) val.setOnClickListener(v -> openList.run());
        box.addView(val);
        box.addView(Ui.pill(this, "›", Ui.GREY, v -> { step.accept(1); refreshChordUi(); }), new LinearLayout.LayoutParams(WRAP, WRAP));
        ((TextView) box.getChildAt(3)).setMinWidth(Ui.dp(40));
        chordRefreshers.add(() -> val.setText(value.get()));
        bar.addView(box);
        return box;
    }

    private void pickFrom(String title, String[] items, int current, java.util.function.IntConsumer set) {
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(title)
                .setSingleChoiceItems(items, current, (d, w) -> { set.accept(w); d.dismiss(); refreshChordUi(); })
                .show();
    }

    private void refreshChordUi() {
        boolean on = project.chordMode;
        chordToggle.setText(on ? "One-finger chords: ON" : "One-finger chords: OFF");
        Ui.setPillColor(chordToggle, on ? Ui.BLUE : Ui.GREY);
        for (View v : chordViews) v.setVisibility(on ? View.VISIBLE : View.GONE);
        for (Runnable r : chordRefreshers) r.run();
        if (on && chordName.getText().length() == 0) chordName.setText("Play a key");
        keyboard.setScale(on ? project.chordKey : -1, project.chordScale);
    }

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onResume() {
        super.onResume();
        engine.start();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (project == null) return;
        if (recording) finishRecording();
        else engine.post(() -> engine.mixer.stop());
        keyboard.releaseAll();
        engine.post(() -> engine.mixer.synth.releaseAll());
        handler.removeCallbacks(tick);
        engine.shutdown();
        engine.mixer.stop();
        engine.mixer.synth.releaseAll();
        engine.mixer.seek(project.cursor);
        save();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
        if (project != null && isFinishing()) store.cleanAudio(project);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
    }

    private void save() { store.saveProject(project, true); }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            Mixer m = engine.mixer;
            boolean moving = m.playing || m.counting || recording;
            if (moving) timeline.follow();
            timeline.invalidate();
            updateStatus();
            handler.postDelayed(this, moving ? 30 : 120);
        }
    };

    private void updateStatus() {
        Mixer m = engine.mixer;
        String s;
        int col;
        if (m.counting) {
            long beat = project.beatLen();
            s = "COUNT-IN " + Math.min(4, m.countInPos / beat + 1);
            col = Ui.ORANGE;
        } else if (recording) {
            s = "● REC";
            col = Ui.RED;
        } else if (m.playing) {
            s = "PLAYING";
            col = Ui.BLUE;
        } else {
            s = "READY";
            col = Ui.GREEN;
        }
        if (!s.equals(statusPill.getText().toString())) {
            statusPill.setText(s);
            statusPill.setBackground(Ui.round(col, 20));
            statusPill.setTextColor(col == Ui.BLUE || col == Ui.RED ? Ui.TEXT : 0xFF000000);
        }
        playBtn.setTextColor(m.playing && !recording ? Ui.GREEN : Ui.TEXT);
    }

    // ------------------------------------------------------------ audio state

    private void loadSource(String src) {
        if (audio.containsKey(src)) return;
        short[] d = store.readAudio(project.id, src);
        audio.put(src, d);
        peaks.put(src, computePeaks(d));
    }

    private static float[] computePeaks(short[] d) {
        int step = TimelineView.PEAK_STEP;
        float[] pk = new float[(d.length + step - 1) / step];
        for (int i = 0; i < pk.length; i++) {
            int m = 0;
            int end = Math.min(d.length, (i + 1) * step);
            for (int j = i * step; j < end; j++) { int v = Math.abs(d[j]); if (v > m) m = v; }
            pk[i] = Math.min(1f, m / 32768f * 1.15f);
        }
        return pk;
    }

    private Mixer.State buildState() {
        Mixer.State s = new Mixer.State();
        List<Mixer.ClipPlay> cps = new ArrayList<>();
        for (Project.Clip c : project.clips) {
            short[] d = audio.get(c.src);
            if (d == null) continue;
            Mixer.ClipPlay cp = new Mixer.ClipPlay();
            cp.data = d; cp.srcStart = c.srcStart; cp.len = c.len; cp.start = c.start;
            if (c.cents != 0) cp.shifted = pitchCache.get(c.cacheKey());
            cps.add(cp);
        }
        s.clips = cps.toArray(new Mixer.ClipPlay[0]);
        List<Project.Note> flat = project.flatNotes();
        Mixer.NotePlay[] ns = new Mixer.NotePlay[flat.size()];
        for (int i = 0; i < ns.length; i++) {
            Project.Note n = flat.get(i);
            Mixer.NotePlay np = new Mixer.NotePlay();
            np.pitch = n.pitch; np.vel = n.vel; np.start = n.start; np.end = n.end();
            ns[i] = np;
        }
        s.notes = ns;
        s.vocalGain = project.vocalMute ? 0 : project.vocalVol * project.vocalVol * 1.4f;
        s.keysGain = project.keysMute ? 0 : project.keysVol * project.keysVol * 1.4f;
        s.metronome = project.metronome;
        s.tempo = project.tempo;
        s.instrument = project.instrument;
        return s;
    }

    private void pushState() { engine.setState(buildState()); }

    private void schedulePitch(Project.Clip c) {
        if (c.cents == 0) return;
        String key = c.cacheKey();
        if (pitchCache.containsKey(key) || pitchPending.contains(key)) return;
        short[] d = audio.get(c.src);
        if (d == null) return;
        pitchPending.add(key);
        final int st = c.srcStart, len = c.len, cents = c.cents;
        try {
            worker.submit(() -> {
                float[] out = PitchShift.process(d, st, len, cents);
                runOnUiThread(() -> {
                    pitchPending.remove(key);
                    pitchCache.put(key, out);
                    pushState();
                    refreshSelection();
                });
            });
        } catch (Exception ignored) { pitchPending.remove(key); }
    }

    // ------------------------------------------------------------ transport

    private long latencySamples() { return (long) store.latencyMs() * SR / 1000; }

    private void play() {
        if (recording) return;
        Mixer m = engine.mixer;
        if (m.playing) return;
        final long from = project.cursor;
        engine.post(() -> engine.mixer.play(from));
    }

    /** Jumps to the very beginning of the song, like the Home key. */
    private void backToStart() {
        if (recording) return;
        project.cursor = 0;
        engine.post(() -> engine.mixer.seek(0));
        timeline.follow();
        timeline.invalidate();
    }

    private void stop() {
        if (recording) { finishRecording(); return; }
        Mixer m = engine.mixer;
        boolean wasMoving = m.playing || m.counting;
        if (!wasMoving) project.cursor = 0; // second press: back to the start of the song
        final long to = project.cursor;
        engine.post(() -> { engine.mixer.stop(); engine.mixer.seek(to); });
        timeline.follow();
    }

    private void record(boolean precount) {
        if (recording) { finishRecording(); return; }
        if (!project.vocalArm && !project.keysArm) {
            toast("Turn on the red record button of a track first");
            return;
        }
        boolean mic = project.vocalArm;
        if (mic && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
            toast("Allow the microphone, then press record again");
            return;
        }
        beginEdit();
        selected = null;
        refreshSelection();
        recNotes.clear();
        recording = true;
        recordStart = project.cursor;
        heldNotes.clear();
        final Recorder r = mic ? new Recorder() : null;
        recorder = r;
        final long from = project.cursor;
        final boolean waitForMic = r != null && !precount;
        engine.post(() -> engine.mixer.startWithCountIn(from, precount ? 4 : 0, r, waitForMic));
        if (r != null) {
            boolean ok = r.start((AudioManager) getSystemService(AUDIO_SERVICE), () -> engine.post(() -> engine.mixer.signalReady()));
            if (!ok) {
                toast("Couldn't open the microphone");
                recorder = null;
                engine.post(() -> engine.mixer.signalReady());
            }
        }
        Ui.setPillColor(precount ? preBtn : recBtn, Ui.RED);
    }

    private void finishRecording() {
        recording = false;
        Mixer m = engine.mixer;
        long now = m.pos;
        engine.post(() -> engine.mixer.stop());
        // keyboard notes still held
        for (Map.Entry<Integer, long[]> e : heldNotes.entrySet()) addRecordedNote(e.getKey(), e.getValue(), notePos(now));
        heldNotes.clear();
        // the keyboard take becomes one clip, from where recording started, rounded up to whole bars
        if (!recNotes.isEmpty()) {
            long bar = project.barLen();
            long start = Math.min(recordStart, recNotes.get(0).start);
            long last = 0;
            for (Project.Note n : recNotes) last = Math.max(last, n.end());
            long len = Math.max(bar, ((last - start + bar - 1) / bar) * bar);
            Project.MidiClip mc = new Project.MidiClip();
            mc.start = start; mc.len = len; mc.offset = 0;
            for (Project.Note n : recNotes) { Project.Note x = n.copy(); x.start -= start; mc.notes.add(x); }
            project.midiClips.add(mc);
            selected = mc;
            recNotes.clear();
        }

        Recorder r = recorder;
        recorder = null;
        if (r != null) {
            short[] data = r.stop();
            long mark = r.markCount;
            if (mark >= 0) {
                int from = (int) Math.min(data.length, mark + latencySamples());
                int len = data.length - from;
                if (len > SR / 5) {
                    String src = Store.newId();
                    store.writeAudio(project.id, src, data, data.length);
                    audio.put(src, data);
                    peaks.put(src, computePeaks(data));
                    Project.Clip c = new Project.Clip();
                    c.src = src; c.srcStart = from; c.len = len; c.start = r.markPos;
                    makeRoomFor(c);
                    project.clips.add(c);
                    selected = c;
                }
            }
        }
        Ui.setPillColor(recBtn, 0xFF9B1C22);
        Ui.setPillColor(preBtn, 0xFF9B1C22);
        final long to = recordStart;
        project.cursor = to;
        engine.post(() -> engine.mixer.seek(to));
        pushState();
        refreshSelection();
        save();
        timeline.invalidate();
    }

    /** A new take replaces whatever vocal audio was underneath it. */
    private void makeRoomFor(Project.Clip n) {
        long s = n.start, e = n.end();
        List<Project.Clip> out = new ArrayList<>();
        for (Project.Clip c : project.clips) {
            if (c.end() <= s || c.start >= e) { out.add(c); continue; }
            if (c.start < s) { // keep the part before
                Project.Clip a = c.copy();
                a.len = (int) (s - c.start);
                out.add(a);
            }
            if (c.end() > e) { // keep the part after
                Project.Clip b = c.copy();
                int cut = (int) (e - c.start);
                b.srcStart += cut; b.len -= cut; b.start = e;
                out.add(b);
            }
        }
        project.clips = out;
        for (Project.Clip c : out) if (c.cents != 0) schedulePitch(c);
    }

    // ------------------------------------------------------------ keyboard

    private long notePos(long renderedPos) {
        return Math.max(0, renderedPos - latencySamples() / 2);
    }

    // Keys pressed -> notes sounding. With one-finger chords a key makes several notes;
    // a note shared by two held chords keeps sounding until both keys are released.
    private final Map<Integer, int[]> keyChords = new HashMap<>();
    private final int[] noteRefs = new int[128];

    @Override
    public void onKeyDown(int key, int vel) {
        if (keyChords.containsKey(key)) onKeyUp(key);
        int[] notes;
        if (project.chordMode) {
            int[] root = new int[1];
            notes = ChordMaker.build(key, project.chordKey, project.chordScale, project.chordShape,
                    project.chordNotes, project.chordInv, root);
            chordName.setText(ChordMaker.name(notes, root[0]));
        } else {
            notes = new int[]{key};
        }
        keyChords.put(key, notes);
        for (int n : notes) {
            if (n < 0 || n > 127) continue;
            if (noteRefs[n]++ == 0) noteStart(n, vel);
        }
    }

    @Override
    public void onKeyUp(int key) {
        int[] notes = keyChords.remove(key);
        if (notes == null) return;
        for (int n : notes) {
            if (n < 0 || n > 127 || noteRefs[n] <= 0) continue;
            if (--noteRefs[n] == 0) noteEnd(n);
        }
    }

    private void noteStart(int n, int vel) {
        engine.liveNoteOn(n, vel);
        if (project.chordMode) keyboard.setLit(n, true);
        if (recording && project.keysArm && engine.mixer.playing) {
            heldNotes.put(n, new long[]{notePos(engine.mixer.pos), vel});
        }
    }

    private void noteEnd(int n) {
        engine.liveNoteOff(n);
        keyboard.setLit(n, false);
        long[] h = heldNotes.remove(n);
        if (h != null && recording) addRecordedNote(n, h, notePos(engine.mixer.pos));
    }

    private void addRecordedNote(int note, long[] h, long end) {
        Project.Note n = new Project.Note();
        n.pitch = note; n.vel = (int) h[1]; n.start = h[0];
        n.len = Math.max(SR / 40, end - h[0]);
        recNotes.add(n);
        java.util.Collections.sort(recNotes, (x, y) -> Long.compare(x.start, y.start));
        timeline.invalidate();
    }

    private void shiftOctave(int d) {
        project.octave = Math.max(0, Math.min(7, project.octave + d));
        keyboard.setBaseNote(12 * (project.octave + 1));
        refreshButtons();
    }

    // ------------------------------------------------------------ editing

    @Override
    public void beginEdit() {
        try {
            undo.push(project.editsToJson().toString());
            while (undo.size() > 60) undo.removeLast();
        } catch (Exception ignored) { }
    }

    private void undo() {
        if (recording) return;
        String s = undo.poll();
        if (s == null) { toast("Nothing to undo"); return; }
        try {
            project.editsFromJson(new JSONObject(s));
        } catch (Exception ignored) { }
        selected = null;
        for (Project.Clip c : project.clips) { loadSource(c.src); if (c.cents != 0) schedulePitch(c); }
        pushState();
        refreshSelection();
        timeline.invalidate();
    }

    private void split() {
        if (recording) return;
        long cut = engine.mixer.playing ? engine.mixer.pos : project.cursor;
        long edge = SR / 50;
        Object target = null;
        if (selected instanceof Project.Clip) {
            Project.Clip c = (Project.Clip) selected;
            if (cut > c.start + edge && cut < c.end() - edge) target = c;
        } else if (selected instanceof Project.MidiClip) {
            Project.MidiClip m = (Project.MidiClip) selected;
            if (cut > m.start + edge && cut < m.end() - edge) target = m;
        }
        if (target == null) for (Project.Clip c : project.clips) if (cut > c.start + edge && cut < c.end() - edge) target = c;
        if (target == null) for (Project.MidiClip m : project.midiClips) if (cut > m.start + edge && cut < m.end() - edge) target = m;
        if (target == null) { toast("Put the cursor over a clip to split it"); return; }
        beginEdit();
        if (target instanceof Project.Clip) {
            Project.Clip a = (Project.Clip) target;
            int off = (int) (cut - a.start);
            Project.Clip b = a.copy();
            b.srcStart += off; b.len -= off; b.start = cut;
            a.len = off;
            project.clips.add(project.clips.indexOf(a) + 1, b);
            selected = b;
            schedulePitch(a);
            schedulePitch(b);
        } else {
            Project.MidiClip a = (Project.MidiClip) target;
            long off = cut - a.start;
            Project.MidiClip b = a.copy();
            b.start = cut; b.offset = a.offset + off; b.len = a.len - off;
            a.len = off;
            project.midiClips.add(project.midiClips.indexOf(a) + 1, b);
            selected = b;
        }
        pushState();
        refreshSelection();
        timeline.invalidate();
    }

    /** Copies the selected clip and puts the copy right after it (like Ctrl/Cmd-D in Reaper). */
    private void duplicate() {
        if (recording) return;
        if (selected == null) { toast("Tap a clip first, then Duplicate"); return; }
        beginEdit();
        if (selected instanceof Project.Clip) {
            Project.Clip a = (Project.Clip) selected;
            Project.Clip b = a.copy();
            b.start = a.end();
            project.clips.add(b);
            selected = b;
        } else {
            Project.MidiClip a = (Project.MidiClip) selected;
            Project.MidiClip b = a.copy();
            b.start = a.end();
            project.midiClips.add(b);
            selected = b;
        }
        pushState();
        refreshSelection();
        timeline.invalidate();
    }

    private void deleteSelected() {
        if (recording) return;
        if (selected == null) { toast("Tap a clip first"); return; }
        beginEdit();
        project.clips.remove(selected);
        project.midiClips.remove(selected);
        selected = null;
        pushState();
        refreshSelection();
        timeline.invalidate();
        toast("Clip deleted — Undo brings it back");
    }

    /** Snaps notes to 1/16: the selected keyboard clip, or all of them if none is selected. */
    private void quantize() {
        if (recording) return;
        List<Project.MidiClip> targets = new ArrayList<>();
        if (selected instanceof Project.MidiClip) targets.add((Project.MidiClip) selected);
        else targets.addAll(project.midiClips);
        int count = 0;
        for (Project.MidiClip m : targets) count += m.notes.size();
        if (count == 0) { toast("No keyboard notes to quantize"); return; }
        beginEdit();
        long grid = project.beatLen() / 4;
        for (Project.MidiClip m : targets) {
            long o = m.origin();
            for (Project.Note n : m.notes) {
                long s = Math.round((o + n.start) / (double) grid) * grid;
                long e = Math.round((o + n.end()) / (double) grid) * grid;
                if (e <= s) e = s + grid;
                n.start = s - o;
                n.len = e - s;
            }
        }
        pushState();
        timeline.invalidate();
        toast(targets.size() == 1 ? "Clip snapped to 1/16 — Undo to go back" : "All keyboard clips snapped to 1/16 — Undo to go back");
    }

    private void setCents(Project.Clip c, int cents) {
        if (c == null || c.cents == cents) return;
        beginEdit();
        c.cents = cents;
        schedulePitch(c);
        pushState();
        refreshSelection();
        timeline.invalidate();
    }

    private static String centsText(int c) { return (c > 0 ? "+" : "") + c + " ct"; }

    /** Pitch window for a vocal clip: -100..+100 cents. */
    private void pitchDialog(Project.Clip c) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(Ui.dp(22), Ui.dp(10), Ui.dp(22), Ui.dp(4));
        TextView val = Ui.label(this, centsText(c.cents), 22, Ui.TEXT, true);
        val.setGravity(Gravity.CENTER);
        box.addView(val, new LinearLayout.LayoutParams(MATCH, WRAP));
        Ui.Slider sl = new Ui.Slider(this, -100, 100, c.cents, 0);
        sl.setListener((v, done) -> val.setText(centsText(Math.round(v))));
        box.addView(sl, new LinearLayout.LayoutParams(MATCH, Ui.dp(48)));
        TextView tip = Ui.label(this, "-100 to +100 cents (100 cents = 1 semitone). Double-tap the slider for 0.", 12, Ui.DIM, false);
        box.addView(tip);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Clip pitch")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> setCents(c, Math.round(sl.getValue())))
                .setNeutralButton("Reset to 0", (d, w) -> setCents(c, 0))
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Pop-up when holding a finger on a clip. */
    private void showClipMenu(Object clip) {
        boolean vocal = clip instanceof Project.Clip;
        String[] items = vocal
                ? new String[]{"Delete clip", "Duplicate", "Change pitch…"}
                : new String[]{"Delete clip", "Duplicate", "Quantize notes to 1/16"};
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle(vocal ? "Vocal clip" : "Keyboard clip")
                .setItems(items, (d, w) -> {
                    selected = clip;
                    if (w == 0) deleteSelected();
                    else if (w == 1) duplicate();
                    else if (vocal) pitchDialog((Project.Clip) clip);
                    else quantize();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshSelection() {
        if (dupBtn == null) return;
        if (selected != null && !project.clips.contains(selected) && !project.midiClips.contains(selected)) selected = null;
        boolean has = selected != null;
        boolean vocal = selected instanceof Project.Clip;
        Ui.setPillColor(dupBtn, has ? Ui.BLUE : Ui.GREY);
        dupBtn.setAlpha(has ? 1f : 0.45f);
        Ui.setPillColor(delBtn, has ? 0xFF9B1C22 : Ui.GREY);
        delBtn.setAlpha(has ? 1f : 0.45f);
        pitchBtn.setAlpha(vocal ? 1f : 0.45f);
        if (vocal) {
            Project.Clip c = (Project.Clip) selected;
            boolean busy = c.cents != 0 && !pitchCache.containsKey(c.cacheKey());
            pitchBtn.setText(busy ? "Pitch …" : "Pitch " + centsText(c.cents));
            Ui.setPillColor(pitchBtn, Ui.BLUE);
        } else {
            pitchBtn.setText("Pitch");
            Ui.setPillColor(pitchBtn, Ui.GREY);
        }
        if (timeline != null) timeline.invalidate();
    }

    private void refreshButtons() {
        Ui.setPillColor(metroBtn, project.metronome ? Ui.BLUE : Ui.GREY);
        metroBtn.setText(project.metronome ? "Metronome ON" : "Metronome OFF");
        tempoBtn.setText("Tempo  " + project.tempo);
        for (int i = 0; i < 3; i++) Ui.setPillColor(instBtns[i], project.instrument == i ? Ui.BLUE : Ui.GREY);
        octaveLabel.setText("C" + project.octave);
        sizeToggle.setText(project.keyOctaves == 1 ? "Keys: 1 octave" : "Keys: 2 octaves");
        nameView.setText(project.name);
    }

    private void tempoDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(Ui.dp(20), Ui.dp(10), Ui.dp(20), 0);
        NumberPicker np = new NumberPicker(this);
        np.setMinValue(80);
        np.setMaxValue(150);
        np.setValue(Math.max(80, Math.min(150, project.tempo)));
        np.setWrapSelectorWheel(false);
        box.addView(np);
        Button tap = new Button(this);
        tap.setText("Tap tempo");
        final long[] taps = new long[8];
        final int[] count = {0};
        tap.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            int n = count[0];
            if (n > 0 && now - taps[(n - 1) % 8] > 2000) n = 0; // restart after a pause
            taps[n % 8] = now;
            n++;
            count[0] = n;
            if (n >= 2) {
                int used = Math.min(n, 8);
                long first = taps[(n - used) % 8];
                double avg = (now - first) / (double) (used - 1);
                int bpm = (int) Math.round(60000 / avg);
                np.setValue(Math.max(80, Math.min(150, bpm)));
            }
        });
        box.addView(tap);
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Tempo (80–150 BPM)")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    np.clearFocus();
                    project.tempo = np.getValue();
                    refreshButtons();
                    pushState();
                    timeline.invalidate();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renameProject() {
        android.widget.EditText e = new android.widget.EditText(this);
        e.setText(project.name);
        e.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(Ui.dp(20), Ui.dp(8), Ui.dp(20), 0);
        box.addView(e, new LinearLayout.LayoutParams(MATCH, WRAP));
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Rename idea")
                .setView(box)
                .setPositiveButton("OK", (d, w) -> {
                    String s = e.getText().toString().trim();
                    if (!s.isEmpty()) { project.name = s; refreshButtons(); save(); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ------------------------------------------------------------ export & share

    private String safeName() {
        String s = project.name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "SongDeck idea" : s;
    }

    // What to render: the full mix, the vocal track alone, or the keyboard track as MIDI.
    private static final int KIND_MIX = 0, KIND_VOCAL = 1, KIND_MIDI = 2;
    private static final String[] KIND_LABELS = {
            "Mix — vocal + keyboard (audio .wav)",
            "Vocal Track only (audio .wav)",
            "Keyboard Track only (MIDI .mid)"};

    private boolean canRender(int kind) {
        if (kind == KIND_MIDI && project.flatNotes().isEmpty()) { toast("No keyboard notes yet"); return false; }
        if (kind == KIND_VOCAL && project.clips.isEmpty()) { toast("No vocal recorded yet"); return false; }
        if (kind == KIND_MIX && project.contentEnd() == 0) { toast("Nothing recorded yet"); return false; }
        return true;
    }

    private String fileName(int kind) {
        if (kind == KIND_MIDI) return safeName() + " - Keyboard.mid";
        if (kind == KIND_VOCAL) return safeName() + " - Vocal.wav";
        return safeName() + " - Mix.wav";
    }

    private void exportMenu() {
        if (recording) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Export to a file")
                .setItems(KIND_LABELS, (d, w) -> {
                    if (!canRender(w)) return;
                    createDoc(w == KIND_MIDI ? "audio/midi" : "audio/wav", fileName(w), REQ_EXPORT + w);
                })
                .show();
    }

    private void createDoc(String mime, String name, int req) {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(Intent.EXTRA_TITLE, name);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("No file picker available on this device");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        int kind = req - REQ_EXPORT;
        if (kind < 0 || kind > 2) return;
        runExport(kind, () -> getContentResolver().openOutputStream(uri, "w"), () -> toast("Exported: " + fileName(kind)));
    }

    private interface StreamOpener { OutputStream open() throws Exception; }

    /** Renders on a background thread with a small progress dialog. */
    @SuppressWarnings("deprecation")
    private void runExport(int kind, StreamOpener opener, Runnable done) {
        boolean wav = kind != KIND_MIDI;
        ProgressDialog pd = new ProgressDialog(this, android.R.style.Theme_Material_Dialog_Alert);
        pd.setMessage(wav ? "Rendering audio…" : "Writing MIDI…");
        pd.setCancelable(false);
        pd.show();
        // make sure every pitch-shifted clip is ready
        final List<Project.Clip> need = new ArrayList<>();
        for (Project.Clip c : project.clips) if (c.cents != 0 && !pitchCache.containsKey(c.cacheKey())) need.add(c.copy());
        final Project snapshot = copyForExport();
        new Thread(() -> {
            String err = null;
            try {
                Map<String, float[]> extra = new HashMap<>();
                for (Project.Clip c : need) {
                    short[] d = audio.get(c.src);
                    if (d != null) extra.put(c.cacheKey(), PitchShift.process(d, c.srcStart, c.len, c.cents));
                }
                try (OutputStream os = opener.open()) {
                    if (os == null) throw new Exception("could not open file");
                    if (wav) {
                        Mixer.State s = stateFor(snapshot, extra, kind == KIND_VOCAL);
                        Exporter.writeWav(s, Exporter.renderLength(snapshot), os);
                    } else {
                        Exporter.writeMidi(snapshot, os);
                    }
                }
                runOnUiThread(() -> pitchCache.putAll(extra));
            } catch (Exception e) {
                err = e.getMessage();
            }
            final String fe = err;
            runOnUiThread(() -> {
                try { pd.dismiss(); } catch (Exception ignored) { }
                if (fe == null) done.run();
                else toast("Export failed: " + fe);
            });
        }).start();
    }

    private Project copyForExport() {
        Project p = new Project();
        p.name = project.name; p.tempo = project.tempo; p.instrument = project.instrument;
        p.vocalVol = project.vocalVol; p.keysVol = project.keysVol;
        p.vocalMute = project.vocalMute; p.keysMute = project.keysMute;
        for (Project.Clip c : project.clips) p.clips.add(c.copy());
        for (Project.MidiClip m : project.midiClips) p.midiClips.add(m.copy());
        return p;
    }

    private Mixer.State stateFor(Project p, Map<String, float[]> extra, boolean vocalOnly) {
        Mixer.State s = new Mixer.State();
        List<Mixer.ClipPlay> cps = new ArrayList<>();
        for (Project.Clip c : p.clips) {
            short[] d = audio.get(c.src);
            if (d == null) continue;
            Mixer.ClipPlay cp = new Mixer.ClipPlay();
            cp.data = d; cp.srcStart = c.srcStart; cp.len = c.len; cp.start = c.start;
            if (c.cents != 0) {
                cp.shifted = extra.get(c.cacheKey());
                if (cp.shifted == null) cp.shifted = pitchCache.get(c.cacheKey());
            }
            cps.add(cp);
        }
        s.clips = cps.toArray(new Mixer.ClipPlay[0]);
        List<Project.Note> flat = p.flatNotes();
        Mixer.NotePlay[] ns = new Mixer.NotePlay[flat.size()];
        for (int i = 0; i < ns.length; i++) {
            Project.Note n = flat.get(i);
            Mixer.NotePlay np = new Mixer.NotePlay();
            np.pitch = n.pitch; np.vel = n.vel; np.start = n.start; np.end = n.end();
            ns[i] = np;
        }
        s.notes = vocalOnly ? new Mixer.NotePlay[0] : ns;
        s.vocalGain = (p.vocalMute && !vocalOnly) ? 0 : p.vocalVol * p.vocalVol * 1.4f;
        s.keysGain = (p.keysMute || vocalOnly) ? 0 : p.keysVol * p.keysVol * 1.4f;
        s.metronome = false;
        s.tempo = p.tempo;
        s.instrument = p.instrument;
        return s;
    }

    private void shareMenu() {
        if (recording) return;
        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                .setTitle("Share")
                .setItems(KIND_LABELS, (d, w) -> {
                    if (!canRender(w)) return;
                    boolean wav = w != KIND_MIDI;
                    File dir = ShareProvider.shareDir(this);
                    File[] old = dir.listFiles();
                    if (old != null) for (File f : old) f.delete();
                    File f = new File(dir, fileName(w));
                    runExport(w, () -> new FileOutputStream(f), () -> {
                        Uri u = ShareProvider.uriFor(f);
                        Intent i = new Intent(Intent.ACTION_SEND);
                        i.setType(wav ? "audio/wav" : "audio/midi");
                        i.putExtra(Intent.EXTRA_STREAM, u);
                        i.putExtra(Intent.EXTRA_SUBJECT, f.getName());
                        i.setClipData(ClipData.newRawUri(f.getName(), u));
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(i, "Share " + f.getName()));
                    });
                })
                .show();
    }

    // ------------------------------------------------------------ TimelineView.Host

    @Override public Project project() { return project; }

    @Override public long playhead() {
        Mixer m = engine.mixer;
        return (m.playing || m.counting || recording) ? m.pos : project.cursor;
    }

    @Override public boolean isPlaying() { return engine.mixer.playing; }
    @Override public boolean isRecording() { return recording; }
    @Override public long recordStart() { return recordStart; }
    @Override public float[] peaks(String src) { return peaks.get(src); }
    @Override public Object selected() { return selected; }
    @Override public List<Project.Note> recordingNotes() { return recNotes; }

    @Override
    public int sourceLength(String src) {
        short[] d = audio.get(src);
        return d == null ? 0 : d.length;
    }

    @Override
    public void select(Object c) {
        if (recording) return;
        selected = c;
        refreshSelection();
    }

    @Override
    public void seek(long pos) {
        if (recording) return;
        project.cursor = Math.max(0, pos);
        final long p = project.cursor;
        engine.post(() -> engine.mixer.seek(p));
    }

    @Override
    public void clipsChanged() {
        if (selected instanceof Project.Clip) schedulePitch((Project.Clip) selected); // trimming changes the audio
        pushState();
        refreshSelection();
    }

    @Override
    public void clipMenu(Object clip) { showClipMenu(clip); }

    @Override
    public void toggleArm(int track) {
        if (recording) return;
        if (track == 0) project.vocalArm = !project.vocalArm;
        else project.keysArm = !project.keysArm;
    }

    @Override
    public void toggleMute(int track) {
        if (track == 0) project.vocalMute = !project.vocalMute;
        else project.keysMute = !project.keysMute;
        pushState();
    }

    @Override
    public void setVolume(int track, float v, boolean done) {
        if (track == 0) project.vocalVol = v;
        else project.keysVol = v;
        pushState();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
