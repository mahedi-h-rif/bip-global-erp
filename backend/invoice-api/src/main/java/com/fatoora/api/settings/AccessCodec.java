package com.fatoora.api.settings;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A user's access per feature, stored in app_user.features as "invoices:view|create,quotations:view".
 * Entries written before access was per feature ("invoices,quotations") take the user's old actions column.
 * View is always included for a ticked feature.
 */
public final class AccessCodec {

    private AccessCodec() {}

    public static Map<String, Set<String>> parse(String features, String legacyActions) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        if (features == null || features.isBlank()) return out;
        Set<String> legacy = split(legacyActions, ",");
        for (String item : features.split(",")) {
            String s = item.strip();
            if (s.isEmpty()) continue;
            int colon = s.indexOf(':');
            String key = colon < 0 ? s : s.substring(0, colon);
            Set<String> acts = new TreeSet<>(colon < 0 ? legacy : split(s.substring(colon + 1), "\\|"));
            acts.retainAll(FeatureCatalog.ACTIONS);
            acts.add("view");
            out.put(key, acts);
        }
        return out;
    }

    /** Keeps only known features and actions; a feature with no actions ticked still gets View. */
    public static Map<String, Set<String>> clean(Map<String, ? extends Collection<String>> permissions) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (var f : FeatureCatalog.FEATURES) {
            Collection<String> acts = permissions.get(f.key());
            if (acts == null) continue;
            Set<String> a = new TreeSet<>(acts);
            a.retainAll(FeatureCatalog.ACTIONS);
            a.add("view");
            out.put(f.key(), a);
        }
        return out;
    }

    public static String encode(Map<String, Set<String>> permissions) {
        return permissions.entrySet().stream()
                .map(e -> e.getKey() + ":" + String.join("|", e.getValue()))
                .collect(Collectors.joining(","));
    }

    /** For JSON: feature → sorted list of actions. */
    public static Map<String, List<String>> asLists(Map<String, Set<String>> permissions) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        permissions.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    private static Set<String> split(String s, String sep) {
        Set<String> out = new TreeSet<>();
        if (s == null) return out;
        for (String x : s.split(sep)) if (!x.isBlank()) out.add(x.strip());
        return out;
    }
}
