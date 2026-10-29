package com.fatoora.api.worker;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Suggests which saved worker a name on an imported sheet means. The company's sheets spell names loosely
 * ("Asraful" / "Asraful Islam", "Hridoy Duct" / "Ridoy Duct Moyalem", "Laden ar Bhai" / "Ladden"), so this only
 * suggests; the user confirms every match before anything is saved.
 */
final class WorkerMatcher {

    /** Words that are forms of address or filler in Bangla-English names, not part of the name. */
    private static final Set<String> FILLER = Set.of("bhai", "ar", "er", "vai", "mr", "md", "lok");

    private WorkerMatcher() {}

    /** The best match's id, or null when no saved worker is close enough. */
    static Long suggest(String name, List<Worker> workers) {
        List<String> t = tokens(name);
        if (t.isEmpty()) return null;
        Worker best = null;
        int bestScore = 0;
        for (Worker w : workers) {
            int s = score(t, tokens(w.getName()));
            if (s > bestScore) {
                bestScore = s;
                best = w;
            }
        }
        return bestScore >= 60 ? best.getId() : null;
    }

    /**
     * 100 identical; 90-99 every word of the shorter name matches the longer (more matching words and exact
     * spellings rank higher, so "Hridoy Duct" picks "Ridoy Duct Moyalem" over "Ridoy"); 70+ first words nearly equal.
     */
    static int score(List<String> a, List<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        if (a.equals(b)) return 100;
        List<String> shorter = a.size() <= b.size() ? a : b, longer = shorter == a ? b : a;
        if (shorter.stream().allMatch(x -> longer.stream().anyMatch(y -> close(x, y)))) {
            long exact = shorter.stream().filter(longer::contains).count();
            return (int) Math.min(99, 85 + 5 * Math.min(shorter.size(), 2) + exact);
        }
        if (close(a.getFirst(), b.getFirst())) return 70 + (a.size() > 1 && b.size() > 1 && close(a.get(1), b.get(1)) ? 10 : 0);
        return 0;
    }

    /** Same word allowing one slip in five letters: "hridoy" / "ridoy", "laden" / "ladden", "sakat" / "shakat". */
    static boolean close(String x, String y) {
        if (x.equals(y)) return true;
        int max = Math.max(x.length(), y.length());
        return max >= 4 && distance(x, y) <= Math.max(1, max / 5);
    }

    static List<String> tokens(String name) {
        return Arrays.stream(name.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\s]", " ").split("\\s+"))
                .filter(s -> !s.isBlank() && !FILLER.contains(s))
                .toList();
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1], cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++)
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
