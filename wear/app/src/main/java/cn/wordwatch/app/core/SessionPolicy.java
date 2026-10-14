package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Reconciles a frozen session with newly synced evidence without replacing its authored content. */
public final class SessionPolicy {
    private SessionPolicy() {}
    public static final int MAX_SESSION_TASKS = 120;

    public static JSONObject reconcile(JSONObject source, JSONArray events, JSONObject availableWords, long now, int limit) throws Exception {
        if (source == null || !Instant.ofEpochMilli(now).atZone(StudyEngine.STUDY_ZONE).toLocalDate().toString().equals(source.optString("date"))) return null;
        JSONObject pack = source.optJSONObject("pack");
        if (pack == null) return source.has("feedbackEvent") ? new JSONObject(source.toString()) : null;
        JSONObject s = new JSONObject(source.toString());
        JSONObject frozen = s.getJSONObject("words");
        List<StudyEngine.Task> eligible = StudyEngine.queue(pack, frozen, events, now, limit);
        return reconcilePrepared(s, availableWords, now, eligible);
    }

    /** Reuses an already checked eligible queue. Frozen pack/word definitions are read-only;
     * only the small session envelope and queue are replaced. */
    public static JSONObject reconcilePrepared(JSONObject source, JSONObject availableWords, long now, List<StudyEngine.Task> eligible) throws Exception {
        if (source == null || !Instant.ofEpochMilli(now).atZone(StudyEngine.STUDY_ZONE).toLocalDate().toString().equals(source.optString("date"))) return null;
        JSONObject s = new JSONObject();
        for (java.util.Iterator<String> keys = source.keys(); keys.hasNext();) { String key = keys.next(); s.put(key, source.get(key)); }
        JSONObject frozen = s.getJSONObject("words");
        Set<String> allowed = new HashSet<>();
        for (StudyEngine.Task t : eligible) if (availableWords.has(t.wordId)) allowed.add(t.wordId);
        JSONArray original = s.getJSONArray("queue"), queue = new JSONArray();
        int position = s.optInt("position");
        if (position < 0 || position > original.length()) return null;
        for (int i = 0; i < position; i++) queue.put(original.get(i));
        Set<String> queued = new HashSet<>();
        for (int i = position; i < original.length(); i++) {
            JSONObject task = original.getJSONObject(i); String id = task.getString("wordId");
            boolean feedback = i == position && s.has("feedbackEvent");
            if (feedback || (allowed.contains(id) && !"retry".equals(task.optString("attempt")))) {
                queue.put(task); queued.add(id);
            }
        }
        for (StudyEngine.Task t : eligible) {
            if (queue.length() >= MAX_SESSION_TASKS) break;
            if (allowed.contains(t.wordId) && queued.add(t.wordId) && frozen.has(t.wordId))
                queue.put(new JSONObject().put("wordId", t.wordId).put("review", t.review).put("attempt", "primary"));
        }
        String before = position < original.length() ? original.getJSONObject(position).optString("wordId") : "";
        String after = position < queue.length() ? queue.getJSONObject(position).optString("wordId") : "";
        if (!before.equals(after)) {
            s.remove("questionIndex"); s.remove("feedbackEvent"); s.put("assisted", false).put("revealed", false).put("inputEnabled", false);
        }
        s.put("queue", queue);
        if (position >= queue.length() && !s.has("completedAt")) s.put("completedAt", now);
        return s;
    }
}
