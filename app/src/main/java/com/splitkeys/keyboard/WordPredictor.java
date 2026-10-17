package com.splitkeys.keyboard;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Offline word prediction for SplitKeys: current-word completion plus
 * next-word prediction.
 *
 * Data sources (all on-device, no network):
 *  - assets/words.txt: common English words, most frequent first.
 *  - assets/bigrams.txt: "prev:next1,next2,next3" common word pairs.
 *  - Learned words: words the user actually typed, counted in
 *    SharedPreferences ("splitkeys_learned") so personal vocabulary
 *    (names, places, foods) quickly bubbles to the top.
 */
public class WordPredictor {

    private static final int MAX_SUGGESTIONS = 3;
    private static final int MAX_NEXT_PER_KEY = 4;
    private static final int MAX_LEARNED = 400;
    private static final int MIN_LEARN_LEN = 2;
    private static final int MAX_LEARN_LEN = 24;

    private final List<String> vocab = new ArrayList<>();
    private final Map<String, List<String>> bigrams = new HashMap<>();
    private final Map<String, Integer> learned = new HashMap<>();
    private final SharedPreferences prefs;

    public WordPredictor(Context ctx) {
        prefs = ctx.getSharedPreferences("splitkeys_learned",
                Context.MODE_PRIVATE);
        loadVocab(ctx);
        loadBigrams(ctx);
        loadLearned();
    }

    private void loadVocab(Context ctx) {
        Set<String> seen = new HashSet<>();
        BufferedReader br = null;
        try {
            InputStream in = ctx.getAssets().open("words.txt");
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                String w = line.trim().toLowerCase(Locale.ROOT);
                if (w.isEmpty() || !seen.add(w)) continue; // first wins
                vocab.add(w);
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(br);
        }
    }

    private void loadBigrams(Context ctx) {
        BufferedReader br = null;
        try {
            InputStream in = ctx.getAssets().open("bigrams.txt");
            br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim().toLowerCase(Locale.ROOT);
                int colon = line.indexOf(':');
                if (colon <= 0) continue;
                String prev = line.substring(0, colon).trim();
                if (prev.isEmpty()) continue;
                List<String> nexts = bigrams.get(prev);
                if (nexts == null) {
                    nexts = new ArrayList<>();
                    bigrams.put(prev, nexts);
                }
                for (String n : line.substring(colon + 1).split(",")) {
                    String w = n.trim();
                    if (!w.isEmpty() && !nexts.contains(w)
                            && nexts.size() < MAX_NEXT_PER_KEY) {
                        nexts.add(w);
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly(br);
        }
    }

    private void loadLearned() {
        try {
            String saved = prefs.getString("words", "");
            if (saved == null || saved.isEmpty()) return;
            for (String pair : saved.split(";")) {
                int colon = pair.indexOf(':');
                if (colon <= 0) continue;
                String w = pair.substring(0, colon);
                int count = 0;
                try {
                    count = Integer.parseInt(pair.substring(colon + 1));
                } catch (NumberFormatException ignored) {
                }
                if (!w.isEmpty() && count > 0) learned.put(w, count);
            }
        } catch (Exception ignored) {
        }
    }

    private void saveLearned() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : learned.entrySet()) {
                if (sb.length() > 0) sb.append(';');
                sb.append(e.getKey()).append(':').append(e.getValue());
            }
            prefs.edit().putString("words", sb.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    private static void closeQuietly(BufferedReader br) {
        try {
            if (br != null) br.close();
        } catch (Exception ignored) {
        }
    }

    /** Record that the user typed (or picked) this word. */
    public void learn(String word) {
        if (word == null) return;
        String w = word.trim().toLowerCase(Locale.ROOT);
        if (w.length() < MIN_LEARN_LEN || w.length() > MAX_LEARN_LEN) return;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!Character.isLetter(c) && c != '\'') return;
        }
        Integer count = learned.get(w);
        learned.put(w, count == null ? 1 : count + 1);
        if (learned.size() > MAX_LEARNED) {
            pruneLearned();
        }
        saveLearned();
    }

    /** Drop the least-used learned words when the table gets too big. */
    private void pruneLearned() {
        List<Map.Entry<String, Integer>> entries =
                new ArrayList<>(learned.entrySet());
        Collections.sort(entries,
                new Comparator<Map.Entry<String, Integer>>() {
                    @Override
                    public int compare(Map.Entry<String, Integer> a,
                                       Map.Entry<String, Integer> b) {
                        return b.getValue() - a.getValue();
                    }
                });
        learned.clear();
        for (int i = 0; i < entries.size() && i < MAX_LEARNED; i++) {
            learned.put(entries.get(i).getKey(), entries.get(i).getValue());
        }
    }

    /**
     * Up to 3 suggestions. When prefix is empty (right after a space),
     * these are next-word predictions for prevWord; otherwise they are
     * completions of the word being typed, with words that commonly
     * follow prevWord ranked first.
     */
    public List<String> suggest(String prevWord, String prefix) {        List<String> out = new ArrayList<>(MAX_SUGGESTIONS);
        String prev = prevWord == null ? "" : prevWord;
        String pre = prefix == null ? "" : prefix;

        if (pre.isEmpty()) {
            List<String> nexts = bigrams.get(prev);
            if (nexts != null) {
                for (String w : nexts) {
                    if (out.size() >= MAX_SUGGESTIONS) break;
                    out.add(w);
                }
            }
            return out;
        }

        Set<String> added = new HashSet<>();

        // 1. Words that commonly follow the previous word.
        List<String> nexts = bigrams.get(prev);
        if (nexts != null) {
            for (String w : nexts) {
                if (out.size() >= MAX_SUGGESTIONS) break;
                if (w.startsWith(pre) && added.add(w)) out.add(w);
            }
        }

        // 2. The user's own learned words, most-used first.
        if (out.size() < MAX_SUGGESTIONS && !learned.isEmpty()) {
            List<Map.Entry<String, Integer>> entries =
                    new ArrayList<>(learned.entrySet());
            Collections.sort(entries,
                    new Comparator<Map.Entry<String, Integer>>() {
                        @Override
                        public int compare(Map.Entry<String, Integer> a,
                                           Map.Entry<String, Integer> b) {
                            return b.getValue() - a.getValue();
                        }
                    });
            for (Map.Entry<String, Integer> e : entries) {
                if (out.size() >= MAX_SUGGESTIONS) break;
                String w = e.getKey();
                if (w.startsWith(pre) && added.add(w)) out.add(w);
            }
        }

        // 3. General vocabulary, most frequent first.
        for (String w : vocab) {
            if (out.size() >= MAX_SUGGESTIONS) break;
            if (w.startsWith(pre) && added.add(w)) out.add(w);
        }

        return out;
    }

    // ---- v3.4: dictionary access for the glide decoder ----

    /** Bundled vocabulary in frequency order (most frequent first). */
    public List<String> getVocab() {
        return new ArrayList<>(vocab);
    }

    /** How many times the user typed this word (0 if never learned). */
    public int getLearnedCount(String word) {
        Integer c = learned.get(word);
        return c == null ? 0 : c;
    }

    /** True when next commonly follows prev in the bundled bigrams. */
    public boolean isBigram(String prev, String next) {
        List<String> nexts = bigrams.get(prev);
        return nexts != null && nexts.contains(next);
    }

    /** Full decode dictionary: bundled vocab first, then learned-only words. */
    public List<String> getAllWords() {
        List<String> all = new ArrayList<>(vocab);
        Set<String> seen = new HashSet<>(vocab);
        for (String w : learned.keySet()) {
            if (seen.add(w)) all.add(w);
        }
        return all;
    }
}
