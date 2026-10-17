package com.splitkeys.keyboard;

import android.inputmethodservice.InputMethodService;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * SplitKeys - a big-key keyboard with the split-row QWERTY layout
 * (each standard row is split across two rows so keys can be huge).
 *
 * Privacy: this app declares NO network permissions. It cannot
 * access the internet, so nothing you type can leave the device.
 */
public class SplitKeysIME extends InputMethodService {

    private static final int SHIFT_OFF = 0;
    private static final int SHIFT_ONCE = 1;
    private static final int SHIFT_LOCK = 2;

    private static final String PREFS_NAME = "splitkeys";
    // v3.6: the two high-contrast themes: "stable" | "new" (default "new").
    private static final String PREF_HC_THEME = "hc_theme";

    private int shiftState = SHIFT_OFF;

    private View lettersView;
    private View sym1View;
    private View sym2View;
    private View emojiView;
    private final List<Button> letterKeys = new ArrayList<>();
    private final List<Button> enterKeys = new ArrayList<>();
    private Button shiftKey;
    private int keyTextSize = 24;
    private int keyRowHeightDp = 62;
    private long lastSpaceTime = 0;
    private final List<Button> allButtons = new ArrayList<>();
    private final java.util.Set<Button> emojiButtons = new java.util.HashSet<>();
    private final List<View> keyRows = new ArrayList<>();
    private View emojiScrollView;

    // Word prediction (v3.3): suggestion strip above the letters page.
    private WordPredictor predictor;
    private LinearLayout suggestionStrip;
    private final TextView[] sugViews = new TextView[3];
    private final List<String> currentSuggestions = new ArrayList<>();
    private String currentPrefix = "";
    private boolean suggestionsEnabled = true;
    private boolean suggestionsForField = true;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable deleteRepeater;

    // ---- v3.6: two high-contrast themes; HC is always on ----
    private Theme theme = Theme.DARK;

    private AudioTrack clickTrack;
    private PopupWindow previewPopup;
    private TextView previewText;
    private final Runnable hidePreviewRunnable = new Runnable() {
        @Override
        public void run() {
            if (previewPopup != null && previewPopup.isShowing()) {
                previewPopup.dismiss();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        initClickSound();
        predictor = new WordPredictor(this);
    }

    /** Synthesized keyboard click (not a dial-pad beep): a short filtered
     *  noise burst with a high-frequency tick, played on the music stream
     *  so it works even with system touch sounds off. */
    private void initClickSound() {
        try {
            int sampleRate = 22050;
            int numSamples = sampleRate * 45 / 1000; // 45ms click
            short[] pcm = new short[numSamples];
            java.util.Random rnd = new java.util.Random(12345);
            for (int i = 0; i < numSamples; i++) {
                double t = (double) i / numSamples;
                double decay = Math.exp(-t * 22);
                double noise = rnd.nextDouble() * 2 - 1;
                double tick = Math.sin(2 * Math.PI * 3400 * i / sampleRate)
                        * Math.exp(-t * 45);
                double s = (noise * 0.55 + tick * 0.45) * decay;
                pcm[i] = (short) (s * 26000);
            }
            clickTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    numSamples * 2, AudioTrack.MODE_STATIC);
            clickTrack.write(pcm, 0, numSamples);
        } catch (Exception e) {
            clickTrack = null;
        }
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(hidePreviewRunnable);
        if (previewPopup != null && previewPopup.isShowing()) {
            previewPopup.dismiss();
        }
        if (clickTrack != null) {
            try {
                clickTrack.release();
            } catch (Exception ignored) {
            }
            clickTrack = null;
        }
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        // v3.6: high-contrast theme choice; HC always on.
        theme = Theme.byName(prefHcTheme());
        letterKeys.clear();
        enterKeys.clear();
        shiftKey = null;

        LayoutInflater inflater = getLayoutInflater();
        lettersView = inflater.inflate(R.layout.keyboard_letters, null);
        sym1View = inflater.inflate(R.layout.keyboard_symbols, null);
        sym2View = inflater.inflate(R.layout.keyboard_symbols2, null);
        emojiView = inflater.inflate(R.layout.keyboard_emoji, null);
        emojiScrollView = emojiView.findViewById(R.id.emoji_scroll);
        suggestionStrip = lettersView.findViewById(R.id.suggestion_strip);
        sugViews[0] = lettersView.findViewById(R.id.sug0);
        sugViews[1] = lettersView.findViewById(R.id.sug1);
        sugViews[2] = lettersView.findViewById(R.id.sug2);
        for (int i = 0; i < sugViews.length; i++) {
            final int idx = i;
            final TextView tv = sugViews[i];
            if (tv != null) {
                tv.setOnClickListener(v -> onSuggestionTap(v, idx));
            }
        }
        allButtons.clear();
        emojiButtons.clear();
        keyRows.clear();
        wire(lettersView, true);
        wire(sym1View, false);
        wire(sym2View, false);
        wire(emojiView, false);
        collectRows(lettersView);
        collectRows(sym1View);
        collectRows(sym2View);
        collectRows(emojiView);
        applyDisplayPrefs();
        shiftState = SHIFT_OFF;
        refreshShiftUi();
        return lettersView;
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        shiftState = SHIFT_OFF;
        if (lettersView != null) refreshShiftUi();
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        // Always reopen on the main page, wherever it was closed.
        if (lettersView != null) {
            setInputView(lettersView);
        }
        applyDisplayPrefs(); // pick up any display change from the setup UI
        suggestionsEnabled = prefSuggestions();
        suggestionsForField = fieldAllowsSuggestions(info);
        applySuggestionVisibility();
        updateSuggestions();
        // Auto-capitalize at the start of an empty text field (not passwords).
        try {
            int inputClass = info.inputType & EditorInfo.TYPE_MASK_CLASS;
            int variation = info.inputType & EditorInfo.TYPE_MASK_VARIATION;
            boolean isText = inputClass == EditorInfo.TYPE_CLASS_TEXT
                    && variation != EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                    && variation != EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    && variation != EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD;
            if (isText) {
                InputConnection ic2 = getCurrentInputConnection();
                CharSequence beforeStart = ic2 != null
                        ? ic2.getTextBeforeCursor(1, 0) : null;
                if (beforeStart == null || beforeStart.length() == 0) {
                    shiftState = SHIFT_ONCE;
                    refreshShiftUi();
                }
            }
        } catch (Exception ignored) {
        }
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        String label = "\u23CE"; // return
        if (action == EditorInfo.IME_ACTION_SEARCH) label = "\uD83D\uDD0D"; // search
        else if (action == EditorInfo.IME_ACTION_GO) label = "\u2192";
        else if (action == EditorInfo.IME_ACTION_SEND) label = "\u27A4";
        else if (action == EditorInfo.IME_ACTION_DONE) label = "\u2713";
        else if (action == EditorInfo.IME_ACTION_NEXT) label = "\u2192";
        for (Button b : enterKeys) b.setText(label);
    }

    @Override
    public void onUpdateSelection(int oldSelStart, int oldSelEnd,
                                  int newSelStart, int newSelEnd,
                                  int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd,
                candidatesStart, candidatesEnd);
        // Cursor moved or text changed outside our key handling (e.g. the
        // long-press delete repeater): re-read predictions from the editor.
        updateSuggestions();
    }

    private void wire(View root, final boolean isLetters) {
        List<Button> buttons = new ArrayList<>();
        collectButtons(root, buttons);
        for (final Button b : buttons) {
            Object tagObj = b.getTag();
            if (tagObj == null) continue;
            final String tag = tagObj.toString();
            if (isLetters && tag.startsWith("key:")) letterKeys.add(b);
            if (tag.equals("action:enter")) enterKeys.add(b);
            if (isLetters && tag.equals("action:shift")) shiftKey = b;
            allButtons.add(b);
            // Emoji never scale with the text-size setting: at large sizes
            // they overflow their keys and render as "...".
            if (root == emojiView || tag.equals("action:emoji")) {
                emojiButtons.add(b);
            }

            b.setOnClickListener(v -> onKeyPress(b, isLetters));

            if (tag.equals("action:delete")) {
                b.setOnLongClickListener(v -> {
                    startDeleteRepeat();
                    return true;
                });
                b.setOnTouchListener((v, event) -> {
                    int a = event.getAction();
                    if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                        stopDeleteRepeat();
                    }
                    return false;
                });
            }
        }
    }

    /** Key-label text size chosen in the setup UI (sp). */
    private int prefTextSize() {
        try {
            return getSharedPreferences("splitkeys", MODE_PRIVATE)
                    .getInt("text_size_sp", 24);
        } catch (Exception e) {
            return 24;
        }
    }

    /** Horizontal layouts holding buttons are key rows (for the height setting). */
    private void collectRows(View v) {
        if (v instanceof LinearLayout) {
            LinearLayout ll = (LinearLayout) v;
            if (ll.getOrientation() == LinearLayout.HORIZONTAL) {
                for (int i = 0; i < ll.getChildCount(); i++) {
                    if (ll.getChildAt(i) instanceof Button) {
                        keyRows.add(ll);
                        break;
                    }
                }
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectRows(g.getChildAt(i));
            }
        }
    }

    /** Key-row height chosen in the setup UI (dp). */
    private int prefKeyHeight() {
        try {
            return getSharedPreferences("splitkeys", MODE_PRIVATE)
                    .getInt("key_height_dp", 62);
        } catch (Exception e) {
            return 62;
        }
    }

    /** Re-read display prefs and apply them to every key. */
    private void applyDisplayPrefs() {
        keyTextSize = prefTextSize();
        keyRowHeightDp = prefKeyHeight();
        theme = Theme.byName(prefHcTheme());
        float density = getResources().getDisplayMetrics().density;

        // Key height (bigger tap targets).
        int rowPx = (int) (keyRowHeightDp * density + 0.5f);
        for (View row : keyRows) {
            ViewGroup.LayoutParams lp = row.getLayoutParams();
            if (lp != null && lp.height != rowPx) {
                lp.height = rowPx;
                row.setLayoutParams(lp);
            }
        }

        // Emoji panel always shows 6 rows, so the whole panel tracks the
        // key-height setting exactly like the letters page does.
        if (emojiScrollView != null) {
            ViewGroup.LayoutParams slp = emojiScrollView.getLayoutParams();
            int scrollPx = rowPx * 6;
            if (slp != null && slp.height != scrollPx) {
                slp.height = scrollPx;
                emojiScrollView.setLayoutParams(slp);
            }
        }

        // v3.6: HC styling, per the selected HC theme.
        styleButtonsThemed(allButtons);
        styleStripThemed(sugViews);

        // Keyboard/strip backgrounds follow the HC theme.
        setRootBackgrounds();
    }

    /**
     * v3.6: key styling for one list of buttons. High contrast is always
     * on: the Light theme uses the stock stable resources, the Dark theme
     * uses the fixed v3.5 dark palette.
     */
    private void styleButtonsThemed(List<Button> buttons) {
        for (Button b : buttons) {
            Object tagObj = b.getTag();
            String tag = tagObj == null ? "" : tagObj.toString();
            boolean isShift = tag.equals("action:shift");

            // Text size (dp, not sp: key labels are layout, so the system
            // font-size setting must not scale them and break the keys).
            if (emojiButtons.contains(b)) {
                b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 26);
            } else if (tag.equals("action:to_symbols")) {
                // "123" key stays small so the label never clips, even on
                // narrow/zoomed screens.
                b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            } else if (isShift) {
                // Shift arrow stays +8dp so it keeps matching the other keys.
                b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, keyTextSize + 8);
            } else {
                b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, keyTextSize);
            }

            if (theme.isLight()) {
                styleKeyLightHc(b, tag, isShift);
                continue;
            }
            styleKeyHc(b, tag, isShift);
        }
    }

    /**
     * v3.6: the Light HC theme — the exact high-contrast look of the
     * stable v3.0 build, rebuilt only from the surviving stock resources:
     * letter keys on R.drawable.key_bg_hc (white fill, #212121 2dp stroke)
     * with #212121 bold labels; delete on key_bg_accent with a white
     * bold label; shift key on the stock key_bg/key_bg_red drawables with
     * the accent-colored bold label; space on space_bg with a #212121
     * bold label; other action keys on key_bg with accent-colored bold
     * labels.
     *
     * Judgment call on caps-lock: the stock key_bg_red drawable has a
     * light-pink (#FFEBEE) fill, so a white label on it is invisible
     * (~1.06:1 contrast). Caps-lock therefore keeps the exact stable
     * look: key_bg_red with the accent-colored (teal) bold label.
     */
    private void styleKeyLightHc(Button b, String tag, boolean isShift) {
        b.setTypeface(null, Typeface.BOLD);
        if (isShift) {
            b.setTextColor(getColor(R.color.accent));
            applyShiftBackground(b);
            return;
        }
        if (tag.equals("action:delete")) {
            b.setTextColor(0xFFFFFFFF);
            b.setBackgroundResource(R.drawable.key_bg_accent);
            return;
        }
        if (tag.equals("action:space")) {
            b.setTextColor(getColor(R.color.key_text));
            b.setBackgroundResource(R.drawable.space_bg);
            return;
        }
        if (tag.startsWith("action:")) {
            b.setTextColor(getColor(R.color.accent));
            b.setBackgroundResource(R.drawable.key_bg);
            return;
        }
        b.setTextColor(getColor(R.color.key_text));
        b.setBackgroundResource(R.drawable.key_bg_hc);
    }

    /**
     * v3.6: the New HC theme — the v3.5 dark high-contrast palette:
     * near-black keys with white bold labels and white 2dp outlines;
     * every action key in strong yellow with black labels; caps-lock
     * red with a white label.
     */
    private void styleKeyHc(Button b, String tag, boolean isShift) {
        b.setTypeface(null, Typeface.BOLD);
        if (isShift && shiftState == SHIFT_LOCK) {
            b.setTextColor(0xFFFFFFFF);
            b.setBackground(keyDrawable(0xFFD32F2F, 0xFFFFFFFF, 2));
            return;
        }
        if (tag.startsWith("action:")) {
            b.setTextColor(0xFF000000);
            b.setBackground(keyDrawable(0xFFFFD600, 0xFFFFFFFF, 2));
            return;
        }
        b.setTextColor(0xFFFFFFFF);
        b.setBackground(keyDrawable(0xFF111111, 0xFFFFFFFF, 2));
    }

    /**
     * v3.6: suggestion-strip styling per HC theme — Light: dark bold
     * text on the light strip; New: white bold text on the black strip
     * (setRootBackgrounds handles the background).
     */
    private void styleStripThemed(TextView[] views) {
        for (TextView tv : views) {
            if (tv == null) continue;
            tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, keyTextSize);
            tv.setTypeface(null, Typeface.BOLD);
            tv.setTextColor(theme.isLight()
                    ? getColor(R.color.key_text) : 0xFFFFFFFF);
        }
    }

    /**
     * v3.6: keyboard and strip backgrounds follow the HC theme —
     * the stock kb_bg (#FAFAFA) for Light, black for Dark.
     */
    private void setRootBackgrounds() {
        int bg = theme.isLight() ? getColor(R.color.kb_bg) : 0xFF000000;
        if (lettersView != null) lettersView.setBackgroundColor(bg);
        if (sym1View != null) sym1View.setBackgroundColor(bg);
        if (sym2View != null) sym2View.setBackgroundColor(bg);
        if (emojiView != null) emojiView.setBackgroundColor(bg);
        if (suggestionStrip != null) suggestionStrip.setBackgroundColor(bg);
    }

    private void updateShiftBackground() {
        applyShiftBackground(shiftKey);
    }

    /**
     * v3.6: shift/caps-lock styling per HC theme. Light keeps the exact
     * stable look: stock key_bg/key_bg_red drawables with the
     * accent-colored (teal) bold label in both states (white on the
     * light-pink key_bg_red fill would be invisible, so the label keeps
     * the stable accent color). New uses the fixed dark palette: red
     * lock with a white label, yellow otherwise with a black label —
     * set here too so mid-session caps-lock toggles stay legible.
     */
    private void applyShiftBackground(Button key) {
        if (key == null) return;
        if (theme.isLight()) {
            key.setTextColor(getColor(R.color.accent));
            key.setTypeface(null, Typeface.BOLD);
            key.setBackgroundResource(shiftState == SHIFT_LOCK
                    ? R.drawable.key_bg_red : R.drawable.key_bg);
            return;
        }
        key.setTextColor(shiftState == SHIFT_LOCK
                ? 0xFFFFFFFF : 0xFF000000);
        key.setTypeface(null, Typeface.BOLD);
        key.setBackground(keyDrawable(shiftState == SHIFT_LOCK
                ? 0xFFD32F2F : 0xFFFFD600, 0xFFFFFFFF, 2));
    }

    /** Rounded key background built from theme colors (non-classic themes). */
    private GradientDrawable keyDrawable(int solid, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        float density = getResources().getDisplayMetrics().density;
        d.setCornerRadius(10 * density);
        d.setColor(solid);
        d.setStroke((int) (strokeDp * density + 0.5f), stroke);
        return d;
    }

    private void collectButtons(View v, List<Button> out) {
        if (v instanceof Button) {
            out.add((Button) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collectButtons(g.getChildAt(i), out);
            }
        }
    }

    private void onKeyPress(Button b, boolean isLetters) {
        String tag = b.getTag().toString();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        b.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        playClick();

        if (tag.startsWith("key:")) {
            String ch = tag.substring(4);
            // A separator typed on the symbols/emoji page finishes the
            // current word, so learn it (an apostrophe does not end a word).
            if (!isLetters && isWordSeparator(ch)) {
                learnWordBeforeCursor(ic);
            }
            if (isLetters && shiftState != SHIFT_OFF) ch = ch.toUpperCase();
            ic.commitText(ch, ch.length());
            showKeyPreview(b, ch);
            if (isLetters && shiftState == SHIFT_ONCE) {
                shiftState = SHIFT_OFF;
                refreshShiftUi();
            }
            updateSuggestions();
            return;
        }

        switch (tag) {
            case "action:shift":
                shiftState = (shiftState + 1) % 3;
                refreshShiftUi();
                break;
            case "action:delete":
                deleteChar(ic);
                break;
            case "action:space":
                learnWordBeforeCursor(ic); // the word just finished
                long now = SystemClock.uptimeMillis();
                // Double-space (within 800ms) turns the previous space into ". ".
                boolean doubleSpace = false;
                try {
                    CharSequence beforeSp = ic.getTextBeforeCursor(2, 0);
                    doubleSpace = (now - lastSpaceTime < 800)
                            && beforeSp != null && beforeSp.length() == 2
                            && beforeSp.charAt(1) == ' '
                            && beforeSp.charAt(0) != ' '
                            && beforeSp.charAt(0) != '.'
                            && beforeSp.charAt(0) != '!'
                            && beforeSp.charAt(0) != '?';
                } catch (Exception ignored) {
                }
                if (doubleSpace) {
                    ic.deleteSurroundingText(1, 0);
                    ic.commitText(". ", 1);
                } else {
                    ic.commitText(" ", 1);
                }
                lastSpaceTime = now;
                // Auto-capitalize after ". ", "! " or "? ".
                if (isLetters) {
                    boolean sentenceEnd = false;
                    try {
                        CharSequence ctx = ic.getTextBeforeCursor(2, 0);
                        sentenceEnd = ctx != null && ctx.length() == 2
                                && ctx.charAt(1) == ' '
                                && (ctx.charAt(0) == '.' || ctx.charAt(0) == '!'
                                    || ctx.charAt(0) == '?');
                    } catch (Exception ignored) {
                    }
                    if (sentenceEnd) {
                        shiftState = SHIFT_ONCE;
                    } else if (shiftState == SHIFT_ONCE) {
                        shiftState = SHIFT_OFF;
                    }
                    refreshShiftUi();
                }
                break;
            case "action:enter":
                learnWordBeforeCursor(ic);
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                break;
            case "action:emoji":
                setInputView(emojiView);
                break;
            case "action:to_symbols":
                setInputView(sym1View);
                break;
            case "action:to_letters":
                setInputView(lettersView);
                break;
            case "action:sym_page2":
                setInputView(sym2View);
                break;
            case "action:sym_page1":
                setInputView(sym1View);
                break;
        }
        updateSuggestions();
    }

    /** "Word suggestions" toggle from the setup UI (default on). */
    private boolean prefSuggestions() {
        try {
            return getSharedPreferences("splitkeys", MODE_PRIVATE)
                    .getBoolean("word_suggestions", true);
        } catch (Exception e) {
            return true;
        }
    }

    /** No predictions in password, PIN, phone or date/time fields. */
    private boolean fieldAllowsSuggestions(EditorInfo info) {
        try {
            int inputClass = info.inputType & EditorInfo.TYPE_MASK_CLASS;
            int variation = info.inputType & EditorInfo.TYPE_MASK_VARIATION;
            if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD) {
                return false;
            }
            return inputClass != EditorInfo.TYPE_CLASS_NUMBER
                    && inputClass != EditorInfo.TYPE_CLASS_PHONE
                    && inputClass != EditorInfo.TYPE_CLASS_DATETIME;
        } catch (Exception e) {
            return true;
        }
    }

    private void applySuggestionVisibility() {
        if (suggestionStrip == null) return;
        suggestionStrip.setVisibility(
                suggestionsEnabled && suggestionsForField
                        ? View.VISIBLE : View.GONE);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetter(c) || c == '\'';
    }

    /** A typed character that ends the current word (not a letter, and
     *  not an apostrophe: "don't" stays one word). */
    private static boolean isWordSeparator(String ch) {
        if (ch == null || ch.isEmpty()) return false;
        int cp = ch.codePointAt(0);
        return !Character.isLetter(cp) && cp != '\'';
    }

    private static String stripEdgeApos(String w) {
        int s = 0, e = w.length();
        while (s < e && w.charAt(s) == '\'') s++;
        while (e > s && w.charAt(e - 1) == '\'') e--;
        return w.substring(s, e);
    }

    /** [prefix, prevWord]: the word being typed plus the word before it,
     *  read straight from the editor so deletes and cursor moves stay
     *  in sync. Both lowercased (Locale.ROOT: length-preserving here). */
    private String[] extractContext(InputConnection ic) {
        String[] out = new String[]{"", ""};
        try {
            CharSequence before = ic.getTextBeforeCursor(48, 0);
            if (before == null) return out;
            String text = before.toString();
            int i = text.length();
            while (i > 0 && isWordChar(text.charAt(i - 1))) i--;
            out[0] = stripEdgeApos(
                    text.substring(i).toLowerCase(java.util.Locale.ROOT));
            int j = i;
            while (j > 0 && !isWordChar(text.charAt(j - 1))) j--;
            int k = j;
            while (k > 0 && isWordChar(text.charAt(k - 1))) k--;
            out[1] = stripEdgeApos(
                    text.substring(k, j).toLowerCase(java.util.Locale.ROOT));
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Learn the word sitting right before the cursor, if there is one. */
    private void learnWordBeforeCursor(InputConnection ic) {
        if (predictor == null || ic == null) return;
        String[] ctx = extractContext(ic);
        if (!ctx[0].isEmpty()) predictor.learn(ctx[0]);
    }

    /** Refresh the suggestion strip from the text before the cursor. */
    private void updateSuggestions() {
        currentSuggestions.clear();
        currentPrefix = "";
        if (suggestionStrip == null || predictor == null) return;
        if (!suggestionsEnabled || !suggestionsForField) {
            for (TextView tv : sugViews) {
                if (tv != null) tv.setText("");
            }
            return;
        }
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            String[] ctx = extractContext(ic);
            currentPrefix = ctx[0];
            List<String> s = predictor.suggest(ctx[1], ctx[0]);
            currentSuggestions.addAll(s);
            renderStrip(sugViews, s);
        } catch (Exception ignored) {
        }
    }

    /** Insert the tapped suggestion, replacing the word being typed. */
    private void onSuggestionTap(View v, int index) {
        try {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) return;
            if (index < 0 || index >= currentSuggestions.size()) return;
            String word = currentSuggestions.get(index);
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            playClick();
            if (!currentPrefix.isEmpty()) {
                ic.deleteSurroundingText(currentPrefix.length(), 0);
            }
            String out = word;
            if (shiftState != SHIFT_OFF && !out.isEmpty()) {
                out = Character.toUpperCase(out.charAt(0)) + out.substring(1);
            }
            ic.commitText(out + " ", 1);
            if (predictor != null) predictor.learn(word);
            if (shiftState == SHIFT_ONCE) {
                shiftState = SHIFT_OFF;
                refreshShiftUi();
            }
            updateSuggestions();
        } catch (Exception ignored) {
        }
    }

    private void refreshShiftUi() {
        setShiftKeyVisual(shiftKey);
        // Red background while caps lock is on; the icon itself stays teal.
        updateShiftBackground();
        boolean upper = shiftState != SHIFT_OFF;
        for (Button b : letterKeys) {
            String ch = b.getTag().toString().substring(4);
            b.setText(upper ? ch.toUpperCase() : ch);
        }
    }

    private void setShiftKeyVisual(Button key) {
        if (key == null) return;
        if (shiftState == SHIFT_OFF) {
            key.setText("\u21E7");
        } else if (shiftState == SHIFT_ONCE) {
            key.setText("\u2B06");
        } else {
            key.setText("\u21EA");
        }
    }

    /** Deletes one user-perceived character, keeping surrogate pairs (emoji) intact. */
    private void deleteChar(InputConnection ic) {
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (before != null && before.length() == 2
                && Character.isHighSurrogate(before.charAt(0))
                && Character.isLowSurrogate(before.charAt(1))) {
            ic.deleteSurroundingText(2, 0);
        } else {
            ic.deleteSurroundingText(1, 0);
        }
    }

    /** Magnified preview of the tapped key, shown briefly above it. */
    private void showKeyPreview(View anchor, String text) {
        try {
            if (previewPopup == null) {
                TextView tv = new TextView(this);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 44);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(0xFF212121);
                tv.setBackgroundResource(R.drawable.key_preview_bg);
                float density = getResources().getDisplayMetrics().density;
                int w = (int) (84 * density);
                int h = (int) (104 * density);
                previewPopup = new PopupWindow(tv, w, h, false);
                previewText = tv;
                // Let touches pass through so the preview never swallows a tap
                // on a key underneath it.
                previewPopup.setTouchable(false);
            }
            previewText.setText(text);
            handler.removeCallbacks(hidePreviewRunnable);
            if (previewPopup.isShowing()) {
                previewPopup.dismiss();
            }
            float density = getResources().getDisplayMetrics().density;
            int xoff = (anchor.getWidth() - previewPopup.getWidth()) / 2;
            int yoff = -(anchor.getHeight() + previewPopup.getHeight() + (int) (8 * density));
            previewPopup.showAsDropDown(anchor, xoff, yoff);
            handler.postDelayed(hidePreviewRunnable, 400);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        handler.removeCallbacks(hidePreviewRunnable);
        if (previewPopup != null && previewPopup.isShowing()) {
            previewPopup.dismiss();
        }
    }

    private void playClick() {
        try {
            if (clickTrack != null) {
                if (clickTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    clickTrack.stop();
                }
                clickTrack.reloadStaticData();
                clickTrack.play();
            }
        } catch (Exception ignored) {
        }
    }

    private void startDeleteRepeat() {
        stopDeleteRepeat();
        deleteRepeater = new Runnable() {
            @Override
            public void run() {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    deleteChar(ic);
                    updateSuggestions();
                    handler.postDelayed(this, 60);
                }
            }
        };
        handler.postDelayed(deleteRepeater, 400);
    }

    private void stopDeleteRepeat() {
        if (deleteRepeater != null) {
            handler.removeCallbacks(deleteRepeater);
            deleteRepeater = null;
        }
    }

    // v3.6: two high-contrast themes ("stable" | "new"); "new" is default.

    private String prefHcTheme() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_HC_THEME, "new");
        } catch (Exception e) {
            return "new";
        }
    }

    private void renderStrip(TextView[] views, List<String> s) {
        for (int i = 0; i < views.length; i++) {
            if (views[i] == null) continue;
            String label = i < s.size() ? s.get(i) : "";
            // Match the shift state, the way a typed letter would.
            if (!label.isEmpty() && shiftState != SHIFT_OFF) {
                label = Character.toUpperCase(label.charAt(0))
                        + label.substring(1);
            }
            views[i].setText(label);
        }
    }
}
