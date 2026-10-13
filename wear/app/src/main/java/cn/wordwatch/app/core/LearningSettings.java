package cn.wordwatch.app.core;

import org.json.JSONObject;

/** An absent personal preference follows authored package policy, including future amounts. */
public final class LearningSettings {
    private LearningSettings() {}
    public static int newLimit(JSONObject settings) {
        return switch (settings.optString("limitMode", "package")) {
            case "review" -> 0;
            case "custom" -> Math.max(0, settings.optInt("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT));
            default -> -1;
        };
    }
    public static String label(JSONObject settings, JSONObject pack) {
        int personal = newLimit(settings);
        if (personal == 0) return "只复习";
        int packageLimit = pack == null ? -1 : pack.optInt("newWordLimit", -1);
        if (personal < 0) return packageLimit < 0 ? "跟随词包 · 本包全部候选" : "跟随词包 · 每日 " + packageLimit + " 个新词";
        return "个人每日上限 " + personal + (packageLimit >= 0 && packageLimit < personal ? " · 本包提供上限 " + packageLimit : "");
    }
}
