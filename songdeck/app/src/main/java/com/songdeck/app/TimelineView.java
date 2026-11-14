package com.songdeck.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;

/** Ruler + vocal track + keyboard track, with track headers on the left. */
public class TimelineView extends View {

    public static final int PEAK_STEP = 128;

    public interface Host {
        Project project();
        long playhead();
        boolean isPlaying();
        boolean isRecording();
        long recordStart();
        float[] peaks(String src);
        Project.Clip selected();
        void select(Project.Clip c);
        void seek(long pos);
        void deleteClip(Project.Clip c);
        void deleteNote(Project.Note n);
        void beginEdit();
        void clipMoved();
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
    private static final int NONE = 0, PENDING = 1, SCROLL = 2, MOVE_CLIP = 3, SEEK = 4, VOL = 5;
    private int mode = NONE;
    private int volTrack;
    private float downX, downY;
    private double downView;
    private long clipOrigStart;
    private Project.Clip dragClip;
    private final int slop;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ScaleGestureDetector scaler;
    private boolean scaling;

    public TimelineView(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop();
        spp = SR / (float) Ui.dp(70);
        tp.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        scaler = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(ScaleGestureDetector d) {
                scaling = true;
                mode = NONE;
                handler.removeCallbacks(longPress);
                return true;
            }

            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float fx = d.getFocusX() - headerW;
                double anchor = viewStart + fx * spp;
                float ns = spp / d.getScaleFactor();
                ns = Math.max(SR / (float) Ui.dp(600), Math.min(SR / (float) Ui.dp(4), ns));
                spp = ns;
                viewStart = Math.max(0, anchor - fx * spp);
                invalidate();
                return true;
            }
        });
    }

    public void setHost(Host h) { host = h; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        headerW = Ui.dp(w > Ui.dp(900) ? 170 : 140);
        rulerH = Ui.dp(26);
        laneGap = Ui.dp(5);
        float laneH = (h - rulerH - laneGap * 2) / 2f;
        vocalLane.set(headerW, rulerH + laneGap, w, rulerH + laneGap + laneH);
        keysLane.set(headerW, vocalLane.bottom + laneGap, w, h);
    }

    private float xOf(long sample) { return headerW + (float) ((sample - viewStart) / spp); }
    private long sampleAt(float x) { return Math.max(0, Math.round(viewStart + (x - headerW) * spp)); }

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

        // lanes background
        p.setColor(0xFF0A1726);
        c.drawRect(vocalLane, p);
        p.setColor(0xFF111317);
        c.drawRect(keysLane, p);

        drawPianoRollRows(c, pr);
        drawGrid(c, beat, W);
        drawClips(c, pr);
        drawNotes(c, pr);

        // recording region
        if (host.isRecording() && pr.vocalArm) {
            float x0 = Math.max(headerW, xOf(host.recordStart()));
            float x1 = xOf(host.playhead());
            if (x1 > x0) {
                p.setColor(0x55E5484D);
                c.drawRect(x0, vocalLane.top, x1, vocalLane.bottom, p);
            }
        }

        drawRuler(c, pr, beat, W);

        // playhead
        float px = xOf(host.playhead());
        if (px >= headerW && px <= W) {
            p.setColor(host.isRecording() ? Ui.RED : 0xFFFFFFFF);
            p.setStrokeWidth(Ui.dp(2));
            c.drawLine(px, 0, px, H, p);
        }
        // start cursor marker
        float cx = xOf(pr.cursor);
        if (cx >= headerW - 1 && cx <= W) {
            p.setColor(Ui.BLUE);
            android.graphics.Path tri = new android.graphics.Path();
            tri.moveTo(cx - Ui.dp(7), 0);
            tri.lineTo(cx + Ui.dp(7), 0);
            tri.lineTo(cx, Ui.dp(10));
            tri.close();
            c.drawPath(tri, p);
        }

        drawHeaders(c, pr);
    }

    private void drawRuler(Canvas c, Project pr, long beat, int W) {
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

    private int[] noteRange(Project pr) {
        int lo = 127, hi = 0;
        for (Project.Note n : pr.notes) { lo = Math.min(lo, n.pitch); hi = Math.max(hi, n.pitch); }
        if (lo > hi) { lo = 12 * (pr.octave + 1); hi = lo + 23; }
        lo -= 1; hi += 1;
        while (hi - lo + 1 < 12) { lo--; hi++; }
        return new int[]{Math.max(0, lo), Math.min(127, hi)};
    }

    private void drawPianoRollRows(Canvas c, Project pr) {
        int[] rg = noteRange(pr);
        int rows = rg[1] - rg[0] + 1;
        float rh = keysLane.height() / rows;
        for (int i = 0; i < rows; i++) {
            int n = rg[1] - i;
            int pc = n % 12;
            boolean black = pc == 1 || pc == 3 || pc == 6 || pc == 8 || pc == 10;
            p.setColor(black ? 0xFF0B0C0F : 0xFF14171C);
            float y = keysLane.top + i * rh;
            c.drawRect(keysLane.left, y, keysLane.right, y + rh, p);
            if (pc == 0) {
                p.setColor(0x22FFFFFF);
                c.drawLine(keysLane.left, y + rh, keysLane.right, y + rh, p);
            }
        }
    }

    private void drawClips(Canvas c, Project pr) {
        Project.Clip sel = host.selected();
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
            r.set(Math.max(x0, headerW - Ui.dp(4)), top, x1, bot);
            p.setStyle(Paint.Style.FILL);
            p.setColor(isSel ? 0xFF1C4677 : 0xFF132C4A);
            c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
            // waveform
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
            if (isSel) {
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(Ui.dp(2));
                p.setColor(Ui.BLUE);
                c.drawRoundRect(r, Ui.dp(4), Ui.dp(4), p);
                p.setStyle(Paint.Style.FILL);
            }
            if (cl.cents != 0 && x1 - x0 > Ui.dp(50)) {
                tp.setColor(Ui.ORANGE);
                tp.setTextSize(Ui.dp(11));
                tp.setTextAlign(Paint.Align.LEFT);
                c.drawText((cl.cents > 0 ? "+" : "") + cl.cents + " ct", Math.max(x0, headerW) + Ui.dp(5), top + Ui.dp(13), tp);
            }
        }
    }

    private void drawNotes(Canvas c, Project pr) {
        int[] rg = noteRange(pr);
        int rows = rg[1] - rg[0] + 1;
        float rh = keysLane.height() / rows;
        for (Project.Note n : pr.notes) {
            float x0 = xOf(n.start), x1 = Math.max(x0 + Ui.dp(3), xOf(n.end()));
            if (x1 < headerW || x0 > getWidth()) continue;
            float y = keysLane.top + (rg[1] - n.pitch) * rh;
            r.set(Math.max(x0, headerW), y + 1, x1, y + rh - 1);
            p.setColor(0xFF2F7CF0);
            c.drawRoundRect(r, Ui.dp(2), Ui.dp(2), p);
        }
        if (pr.notes.isEmpty()) {
            tp.setColor(0x55FFFFFF);
            tp.setTextSize(Ui.dp(13));
            tp.setTextAlign(Paint.Align.LEFT);
            c.drawText("Play the keyboard while recording to add notes", headerW + Ui.dp(14), keysLane.centerY() + Ui.dp(5), tp);
        }
    }

    private void drawHeaders(Canvas c, Project pr) {
        p.setColor(Ui.BG);
        c.drawRect(0, 0, headerW, getHeight(), p);
        tp.setTextAlign(Paint.Align.LEFT);
        // ruler corner: position readout
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
            // volume bar
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

    // ---------------------------------------------------------------- touch

    private final Runnable longPress = () -> {
        if (mode != PENDING || host == null) return;
        mode = NONE;
        if (host.isRecording()) return;
        if (vocalLane.contains(downX, downY)) {
            Project.Clip cl = clipAt(downX);
            if (cl != null) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.deleteClip(cl);
            }
        } else if (keysLane.contains(downX, downY)) {
            Project.Note n = noteAt(downX, downY);
            if (n != null) {
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.deleteNote(n);
            }
        }
        invalidate();
    };

    private Project.Clip clipAt(float x) {
        long s = sampleAt(x);
        Project.Clip found = null;
        for (Project.Clip c : host.project().clips) if (s >= c.start && s < c.end()) found = c; // topmost = last
        return found;
    }

    private Project.Note noteAt(float x, float y) {
        Project pr = host.project();
        int[] rg = noteRange(pr);
        int rows = rg[1] - rg[0] + 1;
        float rh = keysLane.height() / rows;
        long s = sampleAt(x);
        long tol = (long) (Ui.dp(6) * spp);
        Project.Note best = null;
        for (Project.Note n : pr.notes) {
            float y0 = keysLane.top + (rg[1] - n.pitch) * rh;
            if (y < y0 - Ui.dp(4) || y > y0 + rh + Ui.dp(4)) continue;
            if (s >= n.start - tol && s <= n.end() + tol) best = n;
        }
        return best;
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (host == null) return false;
        scaler.onTouchEvent(e);
        int a = e.getActionMasked();
        if (a == MotionEvent.ACTION_DOWN) scaling = false;
        if (scaling || e.getPointerCount() > 1) {
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) { scaling = false; mode = NONE; }
            return true;
        }
        float x = e.getX(), y = e.getY();
        switch (a) {
            case MotionEvent.ACTION_DOWN: {
                getParent().requestDisallowInterceptTouchEvent(true);
                downX = x; downY = y; downView = viewStart;
                mode = NONE;
                if (x < headerW) {
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
                if (y < rulerH) {
                    mode = SEEK;
                    host.seek(sampleAt(x));
                    invalidate();
                    return true;
                }
                mode = PENDING;
                handler.postDelayed(longPress, 450);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float dx = x - downX;
                if (mode == VOL) { setVol(x, false); return true; }
                if (mode == SEEK) { host.seek(sampleAt(x)); invalidate(); return true; }
                if (mode == PENDING && (Math.abs(dx) > slop || Math.abs(y - downY) > slop)) {
                    handler.removeCallbacks(longPress);
                    Project.Clip sel = host.selected();
                    if (sel != null && vocalLane.contains(downX, downY) && clipAt(downX) == sel
                            && !host.isRecording() && !host.isPlaying()) {
                        host.beginEdit();
                        dragClip = sel;
                        clipOrigStart = sel.start;
                        mode = MOVE_CLIP;
                    } else {
                        mode = SCROLL;
                    }
                }
                if (mode == SCROLL) {
                    viewStart = Math.max(0, downView - dx * spp);
                    invalidate();
                } else if (mode == MOVE_CLIP && dragClip != null) {
                    long ns = Math.max(0, clipOrigStart + Math.round(dx * spp));
                    long beat = host.project().beatLen();
                    long snap = Math.round(ns / (double) beat) * beat;
                    if (Math.abs(snap - ns) < Ui.dp(8) * spp) ns = snap; // magnet to beats
                    dragClip.start = ns;
                    invalidate();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                handler.removeCallbacks(longPress);
                if (mode == VOL) setVol(x, true);
                else if (mode == MOVE_CLIP) { dragClip = null; host.clipMoved(); }
                else if (mode == PENDING) {
                    // tap: select clip under finger and move the cursor here
                    if (vocalLane.contains(x, y)) host.select(clipAt(x));
                    else host.select(null);
                    host.seek(sampleAt(x));
                }
                mode = NONE;
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                handler.removeCallbacks(longPress);
                if (mode == MOVE_CLIP) { dragClip = null; host.clipMoved(); }
                mode = NONE;
                return true;
        }
        return true;
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
