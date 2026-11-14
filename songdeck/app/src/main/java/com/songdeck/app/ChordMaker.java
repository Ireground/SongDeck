package com.songdeck.app;

/** One key in, a whole chord out (same logic as the Chord Mochi JSFX). */
public final class ChordMaker {

    public static final String[] KEYS = {"C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"};

    public static final String[] SCALE_NAMES = {"Major (Ionian)", "Minor (Aeolian)", "Dorian", "Phrygian",
            "Lydian", "Mixolydian", "Locrian", "Harmonic Minor", "Melodic Minor"};
    static final int[][] SCALES = {
            {0, 2, 4, 5, 7, 9, 11}, {0, 2, 3, 5, 7, 8, 10}, {0, 2, 3, 5, 7, 9, 10}, {0, 1, 3, 5, 7, 8, 10},
            {0, 2, 4, 6, 7, 9, 11}, {0, 2, 4, 5, 7, 9, 10}, {0, 1, 3, 5, 6, 8, 10}, {0, 2, 3, 5, 7, 8, 11},
            {0, 2, 3, 5, 7, 9, 11}};

    public static final String[] SHAPE_NAMES = {"Stacked 3rds", "Sus2", "Sus4", "Add9", "6th", "Power"};
    // which scale steps to stack (0 = root, 2 = 3rd, 4 = 5th ...)
    static final int[][] SHAPES = {
            {0, 2, 4, 6, 8, 10}, {0, 1, 4, 6, 8, 10}, {0, 3, 4, 6, 8, 12},
            {0, 2, 4, 8, 10, 12}, {0, 2, 4, 5, 8, 10}, {0, 4, 7, 11, 14, 18}};

    /** Chord notes (sorted low to high) for the key pressed. rootOut[0] receives the chord's root. */
    public static int[] build(int note, int key, int scale, int shape, int count, int inversion, int[] rootOut) {
        int[] sc = SCALES[Math.max(0, Math.min(8, scale))];
        int[] sh = SHAPES[Math.max(0, Math.min(5, shape))];
        int cnt = Math.max(1, Math.min(6, count));
        int rel = ((note - key) % 12 + 12) % 12;
        int base = note - rel;
        int si = 0;
        for (int i = 0; i < 7; i++) if (sc[i] <= rel) si = i; // out-of-scale keys snap down
        int[] out = new int[cnt];
        for (int j = 0; j < cnt; j++) {
            int idx = si + sh[j];
            out[j] = base + sc[idx % 7] + 12 * (idx / 7);
        }
        if (rootOut != null) rootOut[0] = out[0];
        int inv = Math.max(0, Math.min(inversion, cnt - 1));
        for (int j = 0; j < inv; j++) out[j] += 12;
        java.util.Arrays.sort(out);
        return out;
    }

    /** True if the pitch class is in the scale. */
    public static boolean inScale(int note, int key, int scale) {
        int rel = ((note - key) % 12 + 12) % 12;
        for (int s : SCALES[scale]) if (s == rel) return true;
        return false;
    }

    public static String name(int[] notes, int root) {
        int mask = 0;
        for (int n : notes) mask |= 1 << (((n - root) % 12 + 12) % 12);
        String q = quality(mask);
        String s = KEYS[((root % 12) + 12) % 12] + q;
        int bass = notes[0];
        if (notes.length > 1 && ((bass - root) % 12 + 12) % 12 != 0) s += "/" + KEYS[((bass % 12) + 12) % 12];
        return s;
    }

    private static String quality(int m) {
        switch (m) {
            case 1: return "";
            case 17: return "(no5)";
            case 9: return "m(no5)";
            case 129: return "5";
            case 145: return "";
            case 137: return "m";
            case 73: return "dim";
            case 273: return "aug";
            case 133: return "sus2";
            case 161: return "sus4";
            case 131: return "susb2";
            case 193: return "sus#4";
            case 2193: return "maj7";
            case 1169: return "7";
            case 1161: return "m7";
            case 1097: return "m7b5";
            case 585: return "dim7";
            case 2185: return "mMaj7";
            case 2321: return "maj7#5";
            case 657: return "6";
            case 649: return "m6";
            case 149: return "add9";
            case 141: return "madd9";
            case 181: return "add9(11)";
            case 173: return "madd9(11)";
            case 1185: return "7sus4";
            case 1157: return "7sus2";
            case 2209: return "maj7sus4";
            case 2181: return "maj7sus2";
            case 1189: return "9sus4";
            case 2197: return "maj9";
            case 1173: return "9";
            case 1165: return "m9";
            case 1163: return "m7b9";
            case 1099: return "m7b5b9";
            case 1101: return "m9b5";
            case 1171: return "7b9";
            case 2189: return "mMaj9";
            case 2325: return "maj9#5";
            case 2201: return "maj7#9";
            case 587: return "dim7b9";
            case 2257: return "maj7#11";
            case 661: return "6/9";
            case 653: return "m6/9";
            case 2229: return "maj11";
            case 2261: return "maj9#11";
            case 1205: return "11";
            case 1197: return "m11";
            case 1195: return "m11b9";
            case 1131: return "m11b5b9";
            case 2221: return "mMaj11";
            default: return "*";
        }
    }
}
