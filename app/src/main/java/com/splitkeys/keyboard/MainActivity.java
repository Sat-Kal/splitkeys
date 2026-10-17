package com.splitkeys.keyboard;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;

/** Setup wizard: guides the user through enabling and selecting the keyboard. */
public class MainActivity extends Activity {

    private static final String PREFS_NAME = "splitkeys";
    private static final String PREF_TEXT_SIZE = "text_size_sp";
    private static final String PREF_KEY_HEIGHT = "key_height_dp";
    private static final String PREF_WORD_SUGGESTIONS = "word_suggestions";
    // v3.7: the high-contrast theme choice: "light" | "dark" (default "dark").
    // (The old "theme_name", "high_contrast", "stable"/"new" prefs are ignored now.)
    private static final String PREF_HC_THEME = "hc_theme";
    private static final int KEY_HEIGHT_MIN = 54;
    private static final int KEY_HEIGHT_MAX = 84;
    private static final int KEY_HEIGHT_DEF = 62;
    private static final int TEXT_SIZE_MIN = 18;
    private static final int TEXT_SIZE_MAX = 36;
    private static final int TEXT_SIZE_DEF = 24;

    private Button btnStep1;
    private Button btnStep2;
    private TextView step1Status;
    private TextView step2Status;
    private TextView sizePreview;
    private TextView sizeLabel;
    private TextView keyHeightPreview;
    private TextView keyHeightLabel;
    private String myImeId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        installCrashLogger();
        try {
            initUi();
        } catch (Throwable t) {
            showErrorUi(t);
        }
    }

    /** Save any later crash to a log file so it can be diagnosed. */
    private void installCrashLogger() {
        final Thread.UncaughtExceptionHandler old =
                Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable e) {
                try {
                    File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        PrintWriter pw = new PrintWriter(
                                new FileWriter(new File(dir, "splitkeys-crash.log"), true));
                        pw.println("=== " + new Date() + " ===");
                        e.printStackTrace(pw);
                        pw.close();
                    }
                } catch (Exception ignored) {
                }
                if (old != null) {
                    old.uncaughtException(thread, e);
                }
            }
        });
    }

    private void initUi() {
        setContentView(R.layout.activity_main);
        myImeId = new ComponentName(this, SplitKeysIME.class).flattenToShortString();

        btnStep1 = findViewById(R.id.btn_step1);
        btnStep2 = findViewById(R.id.btn_step2);
        step1Status = findViewById(R.id.step1_status);
        step2Status = findViewById(R.id.step2_status);

        btnStep1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            }
        });

        btnStep2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputMethodManager imm =
                        (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showInputMethodPicker();
                }
            }
        });

        sizePreview = findViewById(R.id.text_size_preview);
        sizeLabel = findViewById(R.id.text_size_label);
        Button btnSmaller = findViewById(R.id.btn_smaller);
        Button btnBigger = findViewById(R.id.btn_bigger);
        btnSmaller.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTextSizePref(getTextSizePref() - 2);
            }
        });
        btnBigger.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setTextSizePref(getTextSizePref() + 2);
            }
        });
        updateSizeUi(getTextSizePref());

        keyHeightPreview = findViewById(R.id.key_height_preview);
        keyHeightLabel = findViewById(R.id.key_height_label);
        SeekBar heightSeek = findViewById(R.id.key_height_seek);
        heightSeek.setMax(KEY_HEIGHT_MAX - KEY_HEIGHT_MIN);
        heightSeek.setProgress(getKeyHeightPref() - KEY_HEIGHT_MIN);
        heightSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateKeyHeightUi(KEY_HEIGHT_MIN + progress);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                setKeyHeightPref(KEY_HEIGHT_MIN + seekBar.getProgress());
            }
        });
        updateKeyHeightUi(getKeyHeightPref());

        final TextView suggStatus = findViewById(R.id.suggestions_status);
        Switch suggSwitch = findViewById(R.id.switch_suggestions);
        suggSwitch.setChecked(getSuggestionsPref());
        suggStatus.setText(getSuggestionsPref() ? "On" : "Off");
        suggSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton v, boolean checked) {
                setSuggestionsPref(checked);
                suggStatus.setText(checked ? "On" : "Off");
            }
        });

        // v3.7: two large theme preview cards (Light / Dark). Tapping a
        // card picks that theme immediately; the selected card shows a
        // thick accent border and a "✓ Selected" label.
        final View lightCard = findViewById(R.id.theme_light_card);
        final View darkCard = findViewById(R.id.theme_dark_card);
        final TextView lightSelected = findViewById(R.id.theme_light_selected);
        final TextView darkSelected = findViewById(R.id.theme_dark_selected);
        updateThemeCards(lightCard, darkCard, lightSelected, darkSelected,
                getHcThemePref());
        View.OnClickListener pickTheme = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setHcThemePref(v.getId() == R.id.theme_light_card
                        ? Theme.LIGHT.name : Theme.DARK.name);
                updateThemeCards(lightCard, darkCard, lightSelected,
                        darkSelected, getHcThemePref());
            }
        };
        lightCard.setOnClickListener(pickTheme);
        darkCard.setOnClickListener(pickTheme);
    }

    /** v3.7: highlight the chosen theme card and show its "✓ Selected" label. */
    private void updateThemeCards(View lightCard, View darkCard,
                                 TextView lightSelected, TextView darkSelected,
                                 String themeName) {
        boolean light = Theme.byName(themeName).isLight();
        lightCard.setBackgroundResource(light
                ? R.drawable.theme_card_selected : R.drawable.theme_card);
        darkCard.setBackgroundResource(light
                ? R.drawable.theme_card : R.drawable.theme_card_selected);
        lightSelected.setVisibility(light ? View.VISIBLE : View.GONE);
        darkSelected.setVisibility(light ? View.GONE : View.VISIBLE);
    }

    /** Last-resort UI: show the startup error on screen instead of just crashing. */
    private void showErrorUi(Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        ScrollView sv = new ScrollView(this);
        TextView tv = new TextView(this);
        tv.setText("SplitKeys ran into a problem on startup:\n\n"
                + t.toString() + "\n\n" + sw.toString()
                + "\nPlease send a screenshot of this to the developer.");
        tv.setTextSize(13);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        sv.addView(tv);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            refreshSteps();
        } catch (Throwable ignored) {
        }
    }

    private void refreshSteps() {
        boolean enabled = isImeEnabled();
        boolean selected = isImeSelected();

        step1Status.setVisibility(enabled ? View.VISIBLE : View.GONE);
        btnStep1.setVisibility(enabled ? View.GONE : View.VISIBLE);

        step2Status.setVisibility(selected ? View.VISIBLE : View.GONE);
        btnStep2.setVisibility(selected ? View.GONE : View.VISIBLE);
        btnStep2.setEnabled(enabled);
        btnStep2.setAlpha(enabled ? 1f : 0.4f);
    }

    private boolean isImeEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_INPUT_METHODS);
        return enabled != null && enabled.contains(myImeId);
    }

    private boolean isImeSelected() {
        String def = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        return myImeId.equals(def);
    }

    private int getTextSizePref() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getInt(PREF_TEXT_SIZE, TEXT_SIZE_DEF);
        } catch (Exception e) {
            return TEXT_SIZE_DEF;
        }
    }

    private void setTextSizePref(int size) {
        size = Math.max(TEXT_SIZE_MIN, Math.min(TEXT_SIZE_MAX, size));
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_TEXT_SIZE, size).apply();
        } catch (Exception ignored) {
        }
        updateSizeUi(size);
    }

    private void updateSizeUi(int size) {
        if (sizePreview != null) {
            sizePreview.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        }
        if (sizeLabel != null) {
            sizeLabel.setText(String.valueOf(size));
        }
    }

    private boolean getSuggestionsPref() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getBoolean(PREF_WORD_SUGGESTIONS, true);
        } catch (Exception e) {
            return true;
        }
    }

    private void setSuggestionsPref(boolean on) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putBoolean(PREF_WORD_SUGGESTIONS, on).apply();
        } catch (Exception ignored) {
        }
    }

    private int getKeyHeightPref() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getInt(PREF_KEY_HEIGHT, KEY_HEIGHT_DEF);
        } catch (Exception e) {
            return KEY_HEIGHT_DEF;
        }
    }

    private void setKeyHeightPref(int h) {
        h = Math.max(KEY_HEIGHT_MIN, Math.min(KEY_HEIGHT_MAX, h));
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putInt(PREF_KEY_HEIGHT, h).apply();
        } catch (Exception ignored) {
        }
        updateKeyHeightUi(h);
    }

    /** v3.7: high-contrast theme choice: "light" | "dark", default "dark".
     *  Old v3.6 values ("stable"/"new") are migrated by Theme.byName(). */
    private String getHcThemePref() {
        try {
            return getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .getString(PREF_HC_THEME, "dark");
        } catch (Exception e) {
            return "dark";
        }
    }

    private void setHcThemePref(String name) {
        try {
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(PREF_HC_THEME, name).apply();
        } catch (Exception ignored) {
        }
    }

    /** Live preview: the sample key resizes as the slider moves. */
    private void updateKeyHeightUi(int h) {        if (keyHeightPreview != null) {
            android.view.ViewGroup.LayoutParams lp = keyHeightPreview.getLayoutParams();
            lp.height = (int) (h * getResources().getDisplayMetrics().density + 0.5f);
            keyHeightPreview.setLayoutParams(lp);
        }
        if (keyHeightLabel != null) {
            keyHeightLabel.setText(h + "dp");
        }
    }
}
