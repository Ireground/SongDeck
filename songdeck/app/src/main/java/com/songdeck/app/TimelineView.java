package com.songdeck.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.List;

/**
 * Ruler + vocal track + keyboard track, with track headers on the left.
 * Both tracks hold clips: touch a clip and drag to move it, drag a selected clip's
 * edge to trim it (snaps to the grid), hold still on a clip for its menu.
 */
public class TimelineView extends View {

    public static final int PEAK_STEP = 128;

    public interface Host {
        Project project();
        long playhead();
        boolean isPlaying();
        boolean isRecording();
        long recordStart();
        List<Project.Note> recordingNotes();
        float[] peaks(String src);
        int sourceLength(String src);
        Object selected();
        void select(Object clip);
        void seek(long pos);
        void beginEdit();
        void clipsChanged();
        void clipMenu(Object clip);
        void toggleArm(int track);
        void toggleMute(int track);
        void setVolume(int track, float v, boolean done);
    }

    private static final int SR = Project.SR;
    private Host host;

    private float spp;          // samples per pixel
    private double viewStart;   // first visible sample
    private int headerW, rulerH, laneGap;
    private final RectF vocalLane = new RectF(), keysLane = new RectF();
    private final RectF[] armBtn = {new RectF(), new RectF()};
    private final RectF[] muteBtn = {new RectF(), new RectF()};
    private final RectF[] volBar = {new RectF(), new RectF()};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private float[] lines = new float[4096];

    // touch
    private static final int NONE = 0, PENDING = 1, SCROLL = 2, MOVE = 3, TRIM_L = 4, TRIM_R = 5,
            RULER = 6, VOL = 7;
    private int mode = NONE;
    private int pendingTrim;      // TRIM_L / TRIM_R if the finger went down on a selected clip's edge
    private Object touchClip;     // clip under the finger at touch down
    private int volTrack;
    private float downX, downY;
    private double downView;
    private long origStart, origLen, origOffset;
    private int origSrcStart;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaler;
    private boolean scaling;
    private double scaleAnchor;

    public TimelineView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        spp = SR / (float) Ui.dp(70);
        tp.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                if (mode == MOVE || mode == TRIM_L || mode == TRIM_R) return false;
                scaling = true;
                mode = NONE;
                handler.removeCallbacks(longPress);
                scaleAnchor = viewStart + (d.getFocusX() - headerW) * spp;
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float ns = spp / d.getScaleFactor();
                spp = Math.max(SR / (float) Ui.dp(900), Math.min(SR / (float) Ui.dp(4), ns));
                // two fingers: zoom around the fingers and follow them sideways
                viewStart = Math.max(0, scaleAnchor - (d.getFocusX() - headerW) * spp);
                invalidate();
                return true;
            }
        });
    }

    public void setHost(Host h) { host = h; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        headerW = Ui.dp(w > Ui.dp(900) ? 170 : 140);
        rulerH = Ui.dp(28);
        laneGap = Ui.dp(5);
        float laneH = (h - rulerH - laneGap * 2) / 2f;
        vocalLane.set(headerW, rulerH + laneGap, w, rulerH + laneGap + laneH);
        keysLane.set(headerW, vocalLane.bottom + laneGap, w, h);
    }

    private float xOf(long sample) { return headerW + (float) ((sample - viewStart) / spp); }
    private long sampleAt(float x) { return Math.max(0, Math.round(viewStart + (x - headerW) * spp)); }

    /** Snap step that suits the zoom: 1/16, 1/8, beat or bar. */
    private long gridStep() {
        long beat = host.project().beatLen();
        long[] steps = {beat / 4, beat / 2, beat, beat * 4};
        for (long s : steps) if (s / spp >= Ui.dp(14)) return s;
        return beat * 4;
    }

    private long snap(long v) {
        long g = gridStep();
        return Math.max(0, Math.round(v / (double) g) * g);
    }

    /** Keeps the playhead on screen while playing. */
    public void follow() {
        if (host == null) return;
        long ph = host.playhead();
        double visible = (getWidth() - headerW) * spp;
        if (ph > viewStart + visible * 0.92 || ph < viewStart) {
            viewStart = Math.max(0, ph - visible * 0.08);
        }
    }

    // ---------------------------------------------------------------- drawing

    @Override
    protected void onDraw(Canvas c) {
        if (host == null) return;
        Project pr = host.project();
        int W = getWidth(), H = getHeight();
        c.drawColor(Ui.BG);
        long beat = pr.beatLen();

        p.setColor(0xFF0A1726);
        c.drawRect(vocalLane, p);
        p.setColor(0xFF111317);
        c.drawRect(keysLane, p);

        drawGrid(c, beat, W);
        drawClips(c, pr);
        drawMidiClips(c, pr);

        if (host.isRecording()) {
            float x0 = Math.max(headerW, xOf(host.recordStart()));
            float x1 = xOf(host.playhead());
            if (x1 > x0) {
                p.setColor(0x44E5484D);
                if (pr.vocalArm) c.drawRect(x0, vocalLane.top, x1, vocalLane.bottom, p);
                if (pr.keysArm) c.drawRect(x0, keysLane.top, x1, keysLane.bottom, p);
            }
            drawRecordingNotes(c, pr);
        }

        drawRuler(c, beat, W);

        float px = xOf(host.playhead());
        if (px >= headerW && px <= W) {
            p.setColor(host.isRecording() ? Ui.RED : 0xFFFFFFFF);
            p.setStrokeWidth(Ui.dp(2));
            c.drawLine(px, 0, px, H, p);
        }
        float cx = xOf(pr.cursor);
        if (cx >= headerW - 1 && cx <= W) {
            p.setColor(Ui.BLUE);
            Path tri = new Path();
            tri.moveTo(cx - Ui.dp(7), 0);
            tri.lineTo(cx + Ui.dp(7), 0);
            tri.lineTo(cx, Ui.dp(10));
            tri.close();
            c.drawPath(tri, p);
        }

        drawHeaders(c, pr);
    }

    private void drawRuler(Canvas c, long beat, int W) {
        p.setColor(Ui.PANEL);
        c.drawRect(headerW, 0, W, rulerH, p);
        long first = (long) Math.max(0, Math.floor(viewStart / beat));
        long last = (long) Math.ceil((viewStart + (W - headerW) * spp) / beat);
        float beatPx = beat / spp;
        tp.setColor(Ui.DIM);
        tp.setTextSize(Ui.dp(11));
        tp.setTextAlign(Paint.Align.LEFT);
        p.setStrokeWidth(Ui.dp(1));
        for (long b = first; b <= last; b++) {
            float x = xOf(b * beat);
            if (x < headerW) continue;
            boolean bar = b % 4 == 0;
            if (!bar && beatPx < Ui.dp(5)) continue;
            p.setColor(bar ? 0xFF59616E : 0xFF353B45);
            c.drawLine(x, bar ? 0 : rulerH * 0.55f, x, rulerH, p);
            if (bar && (beatPx * 4 > Ui.dp(26) || (b / 4) % 4 == 0)) {
                c.drawText(String.valueOf(b / 4 + 1), x + Ui.dp(4), rulerH * 0.55f, tp);
            }
        }
    }

    private void drawGrid(Canvas c, long beat, int W) {
        long first = (long) Math.max(0, Math.floor(viewStart / beat));
        long last = (long) Math.ceil((viewStart + (W - headerW) * spp) / beat);
        float beatPx = beat / spp;
        p.setStrokeWidth(1);
        for (long b = first; b <= last; b++) {
            boolean bar = b % 4 == 0;
            if (!bar && beatPx < Ui.dp(8)) continue;
            float x = xOf(b * beat);
            if (x < headerW) continue;
            p.setColor(bar ? 0x40FFFFFF : 0x18FFFFFF);
            c.drawLine(x, vocalLane.top, x, vocalLane.bottom, p);
            c.drawLine(x, keysLane.top, x, keysLane.bottom, p);
        }
    }

    private void handles(Canvas c, RectF box) {
        p.setColor(0xFFFFFFFF);
        float hw = Ui.dp(5), hh = Math.min(box.height() * 0.5f, Ui.dp(34));
        float cy = box.centerY();
        if (box.left >= headerW - 1) {
            r.set(box.left, cy - hh / 2, box.left + hw, cy + hh / 2);
            c.drawRoundRect(r, Ui.dp(2), Ui.dp(2), p);
        }
        r.set(box.right - hw, cy - hh / 2, box.right, cy + hh / 2);
        c.drawRoundRect(r, Ui.dp(2), Ui.dp(2), p);
    }

    private void drawClips(Canvas c, Project pr) {
        Object sel = host.selected();
        float top = vocalLane.top + Ui.dp(3), bot = vocalLane.bottom - Ui.dp(3);
        float mid = (top + bot) / 2, half = (bot - top) / 2 * 0.92f;
        if (pr.clips.isEmpty() && !host.isRecording()) {
            tp.setColor(0x55FFFFFF);
            tp.setTextSize(Ui.dp(13));
            tp.setTextAlign(Paint.Align.LEFT);
            c.drawText("Press ● to record your voice", headerW + Ui.dp(14), mid + Ui.dp(5), tp);
        }
        for (Project.Clip cl : pr.clips) {
            float x0 = xOf(cl.start), x1 = xOf(cl.end());
            if (x1 < headerW || x0 > getWidth()) continue;
            boolean isSel = cl == sel;
            RectF box = new RectF(Math.max(x0, headerW - Ui.dp(4)), top, x1, bot);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isSel ? 0xFF1C4677 : 0xFF132C4A);
            c.drawRoundRect(box, Ui.dp(4), Ui.dp(4), p);
            float[] pk = host.peaks(cl.src);
            int from = (int) Math.max(Math.ceil(x0), headerW), to = (int) Math.min(Math.floor(x1), getWidth());
            int cnt = 0;
            if (pk != null && to > from) {
                if (lines.length < (to - from) * 4) lines = new float[(to - from) * 4 + 64];
                for (int x = from; x < to; x++) {
                    long a = (long) ((x - x0) * spp) + cl.srcStart;
                    long b = (long) ((x + 1 - x0) * spp) + cl.srcStart;
                    int ia = (int) (a / PEAK_STEP), ib = (int) Math.max(ia + 1, b / PEAK_STEP);
                    float m = 0;
                    for (int i = ia; i < ib && i < pk.length; i++) if (i >= 0) m = Math.max(m, pk[i]);
                    float hgt = Math.max(0.5f, m * half);
                    lines[cnt++] = x; lines[cnt++] = mid - hgt; lines[cnt++] = x; lines[cnt++] = mid + hgt;
                }
                p.setColor(isSel ? 0xFFFFFFFF : 0xFFC9D4E3);
                p.setStrokeWidth(1);
                c.drawLines(lines, 0, cnt, p);
            }
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(isSel ? 2 : 1));
            p.setColor(isSel ? Ui.BLUE : 0xFF24456E);
            c.drawRoundRect(box, Ui.dp(4), Ui.dp(4), p);
            p.setStyle(Paint.Style.FILL);
            if (cl.cents != 0 && x1 - x0 > Ui.dp(50)) {
                tp.setColor(Ui.ORANGE);
                tp.setTextSize(Ui.dp(11));
                tp.setTextAlign(Paint.Align.LEFT);
                c.drawText((cl.cents > 0 ? "+" : "") + cl.cents + " ct", Math.max(x0, headerW) + Ui.dp(8), top + Ui.dp(13), tp);
            }
            if (isSel) handles(c, box);
        }
    }

    private int[] noteRange(Project pr) {
        int lo = 127, hi = 0;
        for (Project.MidiClip m : pr.midiClips)
            for (Project.Note n : m.notes) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
        if (host.isRecording())
            for (Project.Note n : host.recordingNotes()) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
        if (lo > hi) { lo = 12 * (pr.octave + 1); hi = lo + 23; }
        lo -= 1; hi += 1;
        while (hi - lo + 1 < 12) { lo--; hi++; }
        return new int[]{Math.max(0, lo), Math.min(127, hi)};
    }

    private void drawMidiClips(Canvas c, Project pr) {
        Object sel = host.selected();
        int[] rg = noteRange(pr);
        float top = keysLane.top + Ui.dp(3), bot = keysLane.bottom - Ui.dp(3);
        float rh = (bot - top) / (rg[1] - rg[0] + 1);
        if (pr.midiClips.isEmpty() && !host.isRecording()) {
            tp.setColor(0x55FFFFFF);
            tp.setTextSize(Ui.dp(13));
            tp.setTextAlign(Paint.Align.LEFT);
            c.drawText("Play the keyboard while recording to make a clip", headerW + Ui.dp(14), keysLane.centerY() + Ui.dp(5), tp);
        }
        for (Project.MidiClip m : pr.midiClips) {
            float x0 = xOf(m.start), x1 = xOf(m.end());
            if (x1 < headerW || x0 > getWidth()) continue;
            boolean isSel = m == sel;
            RectF box = new RectF(Math.max(x0, headerW - Ui.dp(4)), top, x1, bot);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isSel ? 0xFF0F4F55 : 0xFF0B3236);
            c.drawRoundRect(box, Ui.dp(4), Ui.dp(4), p);
            c.save();
            c.clipRect(box);
            long o = m.origin();
            p.setColor(isSel ? 0xFF7FD8FF : 0xFF2F9CF0);
            for (Project.Note n : m.notes) {
                long s = o + n.start, e = o + n.end();
                if (e <= m.start || s >= m.end()) continue;
                float nx0 = xOf(Math.max(s, m.start)), nx1 = Math.max(nx0 + Ui.dp(2), xOf(Math.min(e, m.end())));
                float y = top + (rg[1] - n.pitch) * rh;
                r.set(nx0, y + 1, nx1, y + Math.max(2, rh - 1));
                c.drawRoundRect(r, Ui.dp(1.5f), Ui.dp(1.5f), p);
            }
            c.restore();
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(Ui.dp(isSel ? 2 : 1));
            p.setColor(isSel ? 0xFF5BE4D0 : 0xFF1C5A60);
            c.drawRoundRect(box, Ui.dp(4), Ui.dp(4), p);
            p.setStyle(Paint.Style.FILL);
            if (isSel) handles(c, box);
        }
    }

    private void drawRecordingNotes(Canvas c, Project pr) {
        int[] rg = noteRange(pr);
        float top = keysLane.top + Ui.dp(3), bot = keysLane.bottom - Ui.dp(3);
        float rh = (bot - top) / (rg[1] - rg[0] + 1);
        p.setColor(0xFFFF8A8E);
        long now = host.playhead();
        for (Project.Note n : host.recordingNotes()) {
            float nx0 = xOf(n.start);
            float nx1 = Math.max(nx0 + Ui.dp(2), xOf(n.len > 0 ? n.end() : now));
            float y = top + (rg[1] - n.pitch) * rh;
            r.set(Math.max(nx0, headerW), y + 1, nx1, y + Math.max(2, rh - 1));
            c.drawRoundRect(r, Ui.dp(1.5f), Ui.dp(1.5f), p);
        }
    }

    private void drawHeaders(Canvas c, Project pr) {
        p.setColor(Ui.BG);
        c.drawRect(0, 0, headerW, getHeight(), p);
        tp.setTextAlign(Paint.Align.LEFT);
        long ph = host.playhead();
        long beat = pr.beatLen();
        long b = ph / beat;
        tp.setColor(Ui.TEXT);
        tp.setTextSize(Ui.dp(13));
        long secs = ph / SR;
        c.drawText(String.format(java.util.Locale.US, "%d.%d   %d:%02d", b / 4 + 1, b % 4 + 1, secs / 60, secs % 60),
                Ui.dp(8), rulerH * 0.72f, tp);

        RectF[] lanes = {vocalLane, keysLane};
        String[] names = {"VOCAL TRACK", "KEYBOARD TRACK"};
        for (int t = 0; t < 2; t++) {
            RectF L = lanes[t];
            r.set(0, L.top, headerW - Ui.dp(5), L.bottom);
            p.setColor(Ui.PANEL2);
            c.drawRoundRect(r, Ui.dp(8), Ui.dp(8), p);
            float x = Ui.dp(10);
            tp.setColor(Ui.TEXT);
            tp.setTextSize(Ui.dp(12));
            float ty = L.top + Ui.dp(20);
            c.drawText(names[t], x, ty, tp);
            if (t == 1) {
                tp.setColor(Ui.BLUE);
                tp.setTextSize(Ui.dp(10));
                c.drawText(Project.INST_NAMES[pr.instrument], x, ty + Ui.dp(14), tp);
            }
            boolean armed = t == 0 ? pr.vocalArm : pr.keysArm;
            boolean muted = t == 0 ? pr.vocalMute : pr.keysMute;
            float vol = t == 0 ? pr.vocalVol : pr.keysVol;
            float bs = Ui.dp(28);
            float by = Math.min(L.bottom - Ui.dp(52), ty + Ui.dp(22));
            by = Math.max(by, ty + Ui.dp(6));
            armBtn[t].set(x, by, x + bs, by + bs);
            muteBtn[t].set(x + bs + Ui.dp(8), by, x + 2 * bs + Ui.dp(8), by + bs);
            p.setColor(armed ? 0xFF4A1C20 : Ui.GREY);
            c.drawRoundRect(armBtn[t], bs / 2, bs / 2, p);
            p.setColor(armed ? Ui.RED : Ui.DIM);
            c.drawCircle(armBtn[t].centerX(), armBtn[t].centerY(), Ui.dp(6), p);
            p.setColor(muted ? Ui.ORANGE : Ui.GREY);
            c.drawRoundRect(muteBtn[t], bs / 2, bs / 2, p);
            tp.setColor(muted ? 0xFF000000 : Ui.TEXT);
            tp.setTextSize(Ui.dp(12));
            tp.setTextAlign(Paint.Align.CENTER);
            c.drawText("M", muteBtn[t].centerX(), muteBtn[t].centerY() + Ui.dp(4), tp);
            tp.setTextAlign(Paint.Align.LEFT);
            float vy = by + bs + Ui.dp(14);
            if (vy < L.bottom - Ui.dp(4)) {
                volBar[t].set(x, vy - Ui.dp(10), headerW - Ui.dp(18), vy + Ui.dp(10));
                p.setColor(Ui.LINE);
                r.set(volBar[t].left, vy - Ui.dp(2), volBar[t].right, vy + Ui.dp(2));
                c.drawRoundRect(r, Ui.dp(2), Ui.dp(2), p);
                float vx = volBar[t].left + vol * volBar[t].width();
                p.setColor(muted ? Ui.GREY : Ui.BLUE);
                r.set(volBar[t].left, vy - Ui.dp(2), vx, vy + Ui.dp(2));
                c.drawRoundRect(r, Ui.dp(2), Ui.dp(2), p);
                p.setColor(Ui.TEXT);
                c.drawCircle(vx, vy, Ui.dp(6), p);
            } else {
                volBar[t].setEmpty();
            }
        }
    }

    // ---------------------------------------------------------------- hit testing

    private Object clipAt(float x, float y) {
        long s = sampleAt(x);
        Project pr = host.project();
        Object found = null;
        if (vocalLane.contains(x, y)) {
            for (Project.Clip c : pr.clips) if (s >= c.start && s < c.end()) found = c; // topmost = last
        } else if (keysLane.contains(x, y)) {
            for (Project.MidiClip m : pr.midiClips) if (s >= m.start && s < m.end()) found = m;
        }
        return found;
    }

    private long startOf(Object o) { return o instanceof Project.Clip ? ((Project.Clip) o).start : ((Project.MidiClip) o).start; }
    private long endOf(Object o) { return o instanceof Project.Clip ? ((Project.Clip) o).end() : ((Project.MidiClip) o).end(); }

    /** If (x,y) is on an edge of the selected clip, which one. */
    private int edgeAt(float x, float y) {
        Object sel = host.selected();
        if (sel == null) return NONE;
        RectF lane = sel instanceof Project.Clip ? vocalLane : keysLane;
        if (!lane.contains(x, y) && !(x >= lane.left - Ui.dp(20) && y >= lane.top && y <= lane.bottom)) return NONE;
        float x0 = xOf(startOf(sel)), x1 = xOf(endOf(sel));
        float grab = Math.min(Ui.dp(18), (x1 - x0) / 3);
        if (Math.abs(x - x1) <= grab) return TRIM_R;
        if (x0 >= headerW && Math.abs(x - x0) <= grab) return TRIM_L;
        return NONE;
    }

    // ---------------------------------------------------------------- touch

    private final Runnable longPress = () -> {
        if (mode != PENDING || host == null || touchClip == null) return;
        mode = NONE;
        if (host.isRecording()) return;
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        host.select(touchClip);
        invalidate();
        host.clipMenu(touchClip);
    };

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (host == null) return false;
        scaler.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) scaling = false;
        if (scaling || (e.getPointerCount() > 1 && mode != MOVE && mode != TRIM_L && mode != TRIM_R)) {
            handler.removeCallbacks(longPress);
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { scaling = false; mode = NONE; }
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (a) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x; downY = y; downView = viewStart;
                mode = NONE;
                touchClip = null;
                if (x < headerW && y > rulerH) {
                    for (int t = 0; t < 2; t++) {
                        if (expand(armBtn[t]).contains(x, y)) { host.toggleArm(t); invalidate(); return true; }
                        if (expand(muteBtn[t]).contains(x, y)) { host.toggleMute(t); invalidate(); return true; }
                        if (!volBar[t].isEmpty() && expand(volBar[t]).contains(x, y)) {
                            mode = VOL; volTrack = t;
                            setVol(x, false);
                            return true;
                        }
                    }
                    return true;
                }
                if (y < rulerH) { mode = RULER; return true; }
                pendingTrim = host.isRecording() ? NONE : edgeAt(x, y);
                touchClip = pendingTrim != NONE ? host.selected() : clipAt(x, y);
                mode = PENDING;
                if (touchClip != null) handler.postDelayed(longPress, 550);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX;
                if (mode == VOL) { setVol(x, false); return true; }
                boolean moved = Math.abs(dx) > slop || Math.abs(y - downY) > slop;
                if (mode == RULER && moved) mode = SCROLL;
                if (mode == PENDING && moved) {
                    handler.removeCallbacks(longPress);
                    if (touchClip != null && !host.isRecording()) {
                        host.beginEdit();
                        host.select(touchClip);
                        rememberOriginal(touchClip);
                        mode = pendingTrim != NONE ? pendingTrim : MOVE;
                    } else {
                        mode = SCROLL;
                    }
                }
                if (mode == SCROLL) {
                    viewStart = Math.max(0, downView - dx * spp);
                } else if (mode == MOVE) {
                    dragMove(dx);
                } else if (mode == TRIM_L || mode == TRIM_R) {
                    dragTrim(x);
                }
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                if (mode == VOL) setVol(x, true);
                else if (mode == MOVE || mode == TRIM_L || mode == TRIM_R) host.clipsChanged();
                else if (mode == RULER) host.seek(sampleAt(x));
                else if (mode == PENDING) {
                    host.select(touchClip);
                    host.seek(sampleAt(x));
                }
                mode = NONE;
                touchClip = null;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (mode == MOVE || mode == TRIM_L || mode == TRIM_R) host.clipsChanged();
                mode = NONE;
                touchClip = null;
                return true;
        }
        return true;
    }

    private void rememberOriginal(Object o) {
        if (o instanceof Project.Clip) {
            Project.Clip c = (Project.Clip) o;
            origStart = c.start; origLen = c.len; origSrcStart = c.srcStart; origOffset = 0;
        } else {
            Project.MidiClip m = (Project.MidiClip) o;
            origStart = m.start; origLen = m.len; origOffset = m.offset; origSrcStart = 0;
        }
    }

    /** Moves by whole grid steps, so a clip that sat on the grid stays on it. */
    private void dragMove(float dx) {
        long g = gridStep();
        long delta = Math.round(dx * spp / g) * g;
        long ns = Math.max(0, origStart + delta);
        if (touchClip instanceof Project.Clip) ((Project.Clip) touchClip).start = ns;
        else ((Project.MidiClip) touchClip).start = ns;
    }

    /** Drags an edge; the edge lands on the grid. */
    private void dragTrim(float x) {
        long pos = snap(sampleAt(x));
        long minLen = Math.max(SR / 20, gridStep());
        long origEnd = origStart + origLen;
        if (touchClip instanceof Project.Clip) {
            Project.Clip c = (Project.Clip) touchClip;
            int srcLen = host.sourceLength(c.src);
            if (mode == TRIM_L) {
                long earliest = origStart - origSrcStart;            // can't go before the recording began
                long ns = Math.max(earliest, Math.min(pos, origEnd - minLen));
                long d = ns - origStart;
                c.start = ns;
                c.srcStart = (int) (origSrcStart + d);
                c.len = (int) (origLen - d);
            } else {
                long latest = origStart + (srcLen - origSrcStart);   // can't go past the recording's end
                long ne = Math.min(latest, Math.max(pos, origStart + minLen));
                c.len = (int) (ne - origStart);
            }
        } else {
            Project.MidiClip m = (Project.MidiClip) touchClip;
            if (mode == TRIM_L) {
                long earliest = Math.max(0, origStart - origOffset); // can't go before the clip's content began
                long ns = Math.max(earliest, Math.min(pos, origEnd - minLen));
                long d = ns - origStart;
                m.start = ns;
                m.offset = origOffset + d;
                m.len = origLen - d;
            } else {
                long ne = Math.max(pos, origStart + minLen);         // MIDI clips can be stretched longer
                m.len = ne - origStart;
            }
        }
    }

    private RectF expand(RectF a) {
        float g = Ui.dp(6);
        return new RectF(a.left - g, a.top - g, a.right + g, a.bottom + g);
    }

    private void setVol(float x, boolean done) {
        RectF b = volBar[volTrack];
        float v = Math.max(0f, Math.min(1f, (x - b.left) / b.width()));
        host.setVolume(volTrack, v, done);
        invalidate();
    }
}
