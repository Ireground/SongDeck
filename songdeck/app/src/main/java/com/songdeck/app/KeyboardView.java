package com.songdeck.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.SparseIntArray;
import android.view.MotionEvent;
import android.view.View;

/** Multi-touch piano keyboard. Pressing lower on a key plays it louder. */
public class KeyboardView extends View {

    public interface Listener {
        void onKeyDown(int note, int vel);
        void onKeyUp(int note);
    }

    private static final int[] WHITE_STEPS = {0, 2, 4, 5, 7, 9, 11};
    private static final boolean[] HAS_BLACK_AFTER = {true, true, false, true, true, true, false};

    private Listener listener;
    private int baseNote = 48; // C3
    private int whiteCount = 15;
    private final SparseIntArray pointerNote = new SparseIntArray();
    private final boolean[] down = new boolean[128];
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private LinearGradient whiteGrad;
    private int gradH = -1;

    public KeyboardView(Context c) { super(c); }

    public void setListener(Listener l) { listener = l; }

    public void setBaseNote(int n) {
        releaseAll();
        baseNote = n;
        invalidate();
    }

    public int getBaseNote() { return baseNote; }

    public void releaseAll() {
        pointerNote.clear();
        for (int n = 0; n < 128; n++) if (down[n]) keyUp(n);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        int byWidth = Math.round(w / (float) Ui.dp(54));
        whiteCount = Math.max(8, Math.min(36, byWidth));
    }

    private int whiteNote(int i) {
        return baseNote + 12 * (i / 7) + WHITE_STEPS[i % 7];
    }

    /** Which note is at (x, y), or -1. */
    private int noteAt(float x, float y) {
        float w = getWidth() / (float) whiteCount;
        float blackH = getHeight() * 0.6f;
        float blackW = w * 0.62f;
        int wi = (int) (x / w);
        if (wi < 0 || wi >= whiteCount) return -1;
        if (y < blackH) {
            // black key to the right of this white key?
            if (HAS_BLACK_AFTER[wi % 7] && wi + 1 < whiteCount) {
                float bx = (wi + 1) * w - blackW / 2;
                if (x >= bx && x <= bx + blackW) return whiteNote(wi) + 1;
            }
            // black key to the left?
            if (wi > 0 && HAS_BLACK_AFTER[(wi - 1) % 7]) {
                float bx = wi * w - blackW / 2;
                if (x >= bx && x <= bx + blackW) return whiteNote(wi - 1) + 1;
            }
        }
        return whiteNote(wi);
    }

    private int velocityAt(float y) {
        float f = Math.max(0f, Math.min(1f, y / getHeight()));
        return Math.round(55 + f * 72);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                int n = noteAt(e.getX(idx), e.getY(idx));
                pointerNote.put(id, n);
                if (n >= 0) keyDown(n, velocityAt(e.getY(idx)));
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    int old = pointerNote.get(id, -1);
                    int n = noteAt(e.getX(i), e.getY(i));
                    if (n != old) { // slide to another key
                        if (old >= 0) keyUp(old);
                        if (n >= 0) keyDown(n, velocityAt(e.getY(i)));
                        pointerNote.put(id, n);
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int idx = e.getActionIndex();
                int id = e.getPointerId(idx);
                int n = pointerNote.get(id, -1);
                if (n >= 0) keyUp(n);
                pointerNote.delete(id);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
        }
        return true;
    }

    private void keyDown(int n, int vel) {
        if (n < 0 || n > 127) return;
        down[n] = true;
        if (listener != null) listener.onKeyDown(n, vel);
        invalidate();
    }

    private void keyUp(int n) {
        if (n < 0 || n > 127) return;
        // only release if no other finger holds it
        int holders = 0;
        for (int i = 0; i < pointerNote.size(); i++) if (pointerNote.valueAt(i) == n) holders++;
        if (holders > 1) return;
        down[n] = false;
        if (listener != null) listener.onKeyUp(n);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        int W = getWidth(), H = getHeight();
        c.drawColor(0xFF08090B);
        float w = W / (float) whiteCount;
        float gap = Math.max(1, Ui.dp(1.5f));
        if (gradH != H) {
            whiteGrad = new LinearGradient(0, 0, 0, H, 0xFFDCDFE4, 0xFFFFFFFF, Shader.TileMode.CLAMP);
            gradH = H;
        }
        p.setTextSize(Ui.dp(11));
        p.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < whiteCount; i++) {
            int n = whiteNote(i);
            r.set(i * w + gap / 2, 0, (i + 1) * w - gap / 2, H);
            if (n <= 127 && down[n]) {
                p.setShader(null);
                p.setColor(0xFF9CC2FF);
            } else {
                p.setShader(whiteGrad);
            }
            c.drawRoundRect(r, Ui.dp(5), Ui.dp(5), p);
            p.setShader(null);
            if (i % 7 == 0) {
                p.setColor(0xFF7A8290);
                c.drawText("C" + (n / 12 - 1), r.centerX(), H - Ui.dp(10), p);
            }
        }
        float blackH = H * 0.6f, blackW = w * 0.62f;
        for (int i = 0; i < whiteCount; i++) {
            if (!HAS_BLACK_AFTER[i % 7] || i + 1 >= whiteCount + 1) continue;
            if (i == whiteCount - 1) continue;
            int n = whiteNote(i) + 1;
            float bx = (i + 1) * w - blackW / 2;
            r.set(bx, -Ui.dp(6), bx + blackW, blackH);
            p.setColor(n <= 127 && down[n] ? Ui.BLUE : 0xFF15171B);
            c.drawRoundRect(r, Ui.dp(5), Ui.dp(5), p);
            if (!(n <= 127 && down[n])) {
                p.setColor(0xFF2E323A);
                r.set(bx + blackW * 0.15f, blackH - Ui.dp(14), bx + blackW * 0.85f, blackH - Ui.dp(5));
                c.drawRoundRect(r, Ui.dp(3), Ui.dp(3), p);
            }
        }
    }
}
