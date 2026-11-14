package com.songdeck.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

/** Shared look: dark panels, rounded pill buttons, blue accent. */
public final class Ui {

    public static final int BG = 0xFF0D0F12;
    public static final int PANEL = 0xFF171A1F;
    public static final int PANEL2 = 0xFF22262D;
    public static final int LINE = 0xFF2C313A;
    public static final int TEXT = 0xFFECEFF4;
    public static final int DIM = 0xFF8A94A6;
    public static final int BLUE = 0xFF3B82F6;
    public static final int RED = 0xFFE5484D;
    public static final int GREEN = 0xFF22A55B;
    public static final int ORANGE = 0xFFE0A030;
    public static final int GREY = 0xFF3A404A;

    private static float density = 2f;

    public static void init(Context c) { density = c.getResources().getDisplayMetrics().density; }

    public static int dp(float v) { return Math.round(v * density); }

    public static GradientDrawable round(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    public static int darker(int c) {
        int a = c >>> 24, r = (c >> 16) & 255, g = (c >> 8) & 255, b = c & 255;
        return (a << 24) | ((int) (r * 0.7) << 16) | ((int) (g * 0.7) << 8) | (int) (b * 0.7);
    }

    public static void setPillColor(TextView t, int color) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, round(darker(color), 12));
        s.addState(new int[]{}, round(color, 12));
        t.setBackground(s);
    }

    public static TextView pill(Context c, String text, int color, View.OnClickListener l) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(TEXT);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setMinWidth(dp(48));
        t.setAllCaps(true);
        setPillColor(t, color);
        if (l != null) t.setOnClickListener(l);
        return t;
    }

    public static TextView label(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** "SONGDECK" with the second half in blue. */
    public static TextView title(Context c, String a, String b, float sp) {
        TextView t = new TextView(c);
        SpannableString s = new SpannableString(a + b);
        s.setSpan(new ForegroundColorSpan(TEXT), 0, a.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new ForegroundColorSpan(BLUE), a.length(), a.length() + b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        return t;
    }

    /** Lays children left to right and wraps to a new line when they don't fit, so nothing is ever hidden. */
    public static class Flow extends android.view.ViewGroup {
        private final int gap;

        public Flow(Context c, int gapDp) {
            super(c);
            gap = dp(gapDp);
        }

        @Override
        protected void onMeasure(int ws, int hs) {
            int maxW = MeasureSpec.getSize(ws) - getPaddingLeft() - getPaddingRight();
            int x = 0, y = 0, rowH = 0;
            for (int i = 0; i < getChildCount(); i++) {
                View ch = getChildAt(i);
                if (ch.getVisibility() == GONE) continue;
                ch.measure(MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                int cw = ch.getMeasuredWidth(), chh = ch.getMeasuredHeight();
                if (x > 0 && x + cw > maxW) { x = 0; y += rowH + gap; rowH = 0; }
                x += cw + gap;
                rowH = Math.max(rowH, chh);
            }
            setMeasuredDimension(MeasureSpec.getSize(ws), y + rowH + getPaddingTop() + getPaddingBottom());
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            int maxW = r - l - getPaddingLeft() - getPaddingRight();
            int n = getChildCount();
            int i = 0, y = getPaddingTop();
            while (i < n) {
                // collect one row
                int x = 0, rowH = 0, j = i;
                while (j < n) {
                    View ch = getChildAt(j);
                    if (ch.getVisibility() == GONE) { j++; continue; }
                    int cw = ch.getMeasuredWidth();
                    if (x > 0 && x + cw > maxW) break;
                    x += cw + gap;
                    rowH = Math.max(rowH, ch.getMeasuredHeight());
                    j++;
                }
                if (j == i) j = i + 1;
                int cx = getPaddingLeft();
                for (int k = i; k < j; k++) {
                    View ch = getChildAt(k);
                    if (ch.getVisibility() == GONE) continue;
                    int cy = y + (rowH - ch.getMeasuredHeight()) / 2;
                    ch.layout(cx, cy, cx + ch.getMeasuredWidth(), cy + ch.getMeasuredHeight());
                    cx += ch.getMeasuredWidth() + gap;
                }
                y += rowH + gap;
                i = j;
            }
        }
    }

    /** Slim horizontal slider. */
    public static class Slider extends View {
        public interface Listener { void onChange(float value, boolean done); }

        private float min, max, value, def;
        private Listener listener;
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF r = new RectF();
        private boolean enabledLook = true;
        private long lastTap;

        public Slider(Context c, float min, float max, float value, float def) {
            super(c);
            this.min = min; this.max = max; this.value = value; this.def = def;
            setMinimumHeight(dp(32));
            setMinimumWidth(dp(120));
        }

        public void setListener(Listener l) { listener = l; }
        public void setValue(float v) { value = Math.max(min, Math.min(max, v)); invalidate(); }
        public float getValue() { return value; }
        public void setEnabledLook(boolean e) { enabledLook = e; invalidate(); }

        @Override
        protected void onDraw(Canvas c) {
            float h = getHeight(), w = getWidth();
            float pad = dp(9);
            float cy = h / 2f;
            p.setColor(LINE);
            r.set(pad, cy - dp(2), w - pad, cy + dp(2));
            c.drawRoundRect(r, dp(2), dp(2), p);
            float frac = (value - min) / (max - min);
            float x = pad + frac * (w - 2 * pad);
            float zero = pad + ((Math.max(min, Math.min(max, def)) - min) / (max - min)) * (w - 2 * pad);
            p.setColor(enabledLook ? BLUE : GREY);
            r.set(Math.min(zero, x), cy - dp(2), Math.max(zero, x), cy + dp(2));
            c.drawRoundRect(r, dp(2), dp(2), p);
            p.setColor(enabledLook ? TEXT : DIM);
            c.drawCircle(x, cy, dp(8), p);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            if (!isEnabled()) return false;
            float pad = dp(9);
            float frac = (e.getX() - pad) / (getWidth() - 2 * pad);
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    getParent().requestDisallowInterceptTouchEvent(true);
                    long now = System.currentTimeMillis();
                    if (now - lastTap < 300) { // double tap = back to default
                        value = def;
                        invalidate();
                        if (listener != null) listener.onChange(value, true);
                        lastTap = 0;
                        return true;
                    }
                    lastTap = now;
                    // fall through
                case MotionEvent.ACTION_MOVE:
                    value = Math.max(min, Math.min(max, min + frac * (max - min)));
                    invalidate();
                    if (listener != null) listener.onChange(value, false);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (listener != null) listener.onChange(value, true);
                    return true;
            }
            return true;
        }
    }
}
