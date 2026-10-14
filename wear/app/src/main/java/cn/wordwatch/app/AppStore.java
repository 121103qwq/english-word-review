package cn.wordwatch.app;

import android.content.Context;
import cn.wordwatch.app.core.StudyEngine;
import cn.wordwatch.app.core.SessionPolicy;
import cn.wordwatch.app.core.LearningSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.time.LocalDate;

public final class AppStore {
    private static AppStore instance;
    private final LearningStorage storage;
    private JSONObject state;
    private String activatedDay = "", eligibleDay = "";
    private Object eligibleEvents, eligibleWords, eligibleWithdrawn, eligiblePack;
    private int eligibleLimit;
    private long eligibleAt, eligibleUntil;
    private java.util.List<StudyEngine.Task> eligible;
    public static synchronized AppStore get(Context context) {
        if (instance == null) instance = new AppStore(context.getApplicationContext());
        return instance;
    }
    private AppStore(Context context) {
        storage = new LearningStorage(context.getFilesDir());
        try {
            state = storage.read();
            if (state == null) {
                state = new JSONObject().put("schemaVersion", 1).put("deviceId", UUID.randomUUID().toString()).put("seq", 0)
                    .put("events", new JSONArray()).put("words", new JSONObject()).put("settings", new JSONObject()
                    .put("owner", "121103qwq").put("repo", "english-word-review-data").put("branch", "main").put("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT));
                write(state);
            }
            // Establish an atomic checkpoint before any compact progress writes on a legacy install.
            if (state.optString("storageGeneration").isEmpty()) write(state);
            if (upgradeDailyLimit(state)) write(state);
            if (!state.getJSONObject("settings").has("limitMode")) {
                state.getJSONObject("settings").put("limitMode", "package"); write(state);
            }
            if (state.optJSONObject("pack") == null) {
                try (InputStream input = context.getAssets().open("bootstrap.json")) {
                    JSONObject pack = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
                    if (pack.has("packageId")) { StudyEngine.validatePackage(pack); insertPackage(state, pack); }
                }
                write(state);
            }
        } catch (Exception e) { throw new IllegalStateException("本机学习数据读取失败，原文件已保留。请勿卸载应用。", e); }
    }
    static boolean upgradeDailyLimit(JSONObject state) throws Exception {
        if (state.optInt("newLimitDefaultVersion") >= 2) return false;
        JSONObject settings = state.getJSONObject("settings");
        if (settings.optInt("newLimit", 15) == 15) settings.put("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT);
        state.put("newLimitDefaultVersion", 2);
        return true;
    }
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
    }
    private JSONObject copy() { try { return new JSONObject(state.toString()); } catch (Exception e) { throw new IllegalStateException(e); } }
    private void write(JSONObject next) throws Exception { state = storage.checkpoint(next); }
    private void writeProgress(JSONObject next) throws Exception { state = storage.progress(next); }
    /** Published state is immutable. Only package-local screen preparation may retain this view. */
    synchronized JSONObject readView() { return state; }
    private JSONObject field(String key) {
        JSONObject value = state.optJSONObject(key);
        try { return value == null ? null : new JSONObject(value.toString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static JSONArray arrayCopy(JSONArray source) throws Exception {
        JSONArray result = new JSONArray();
        for (int i = 0; i < source.length(); i++) result.put(source.get(i));
        return result;
    }
    static void insertPackage(JSONObject next, JSONObject incoming) throws Exception {
        JSONObject packs = next.optJSONObject("packages");
        if (packs == null) packs = new JSONObject();
        JSONObject current = next.optJSONObject("pack");
        if (current != null && !packs.has(current.getString("date"))) packs.put(current.getString("date"), current);
        if (current != null && !current.getString("catalogSha256").equalsIgnoreCase(incoming.optString("catalogSha256")))
            throw new IllegalArgumentException("catalog changed without migration");
        String date = incoming.getString("date");
        JSONObject accepted = StudyEngine.applyPackage(packs.optJSONObject(date), incoming);
        packs.put(date, accepted);
        // Retain a bounded window of dated definitions; answer/undo history is never pruned.
        ArrayList<String> dates = new ArrayList<>(); packs.keys().forEachRemaining(dates::add); Collections.sort(dates);
        while (dates.size() > 15) packs.remove(dates.remove(0));
        next.put("packages", packs);
        activatePackage(next, LocalDate.now(StudyEngine.STUDY_ZONE).toString());
    }
    static boolean activatePackage(JSONObject next, String today) throws Exception {
        JSONObject packs = next.optJSONObject("packages"); if (packs == null) return false;
        ArrayList<String> dates = new ArrayList<>(); packs.keys().forEachRemaining(dates::add); Collections.sort(dates);
        String selected = null;
        for (String date : dates) if (date.compareTo(today) <= 0) selected = date;
        // A future-only pack is visible as downloaded, but StudyEngine gates its new candidates by date.
        if (selected == null && !dates.isEmpty()) selected = dates.get(0);
        if (selected == null) return false;
        JSONObject pack = packs.getJSONObject(selected), old = next.optJSONObject("pack");
        if (old != null && old.toString().equals(pack.toString())) return false;
        JSONObject words = next.getJSONObject("words"), withdrawn = next.optJSONObject("withdrawn");
        if (withdrawn == null) withdrawn = new JSONObject();
        JSONArray list = pack.getJSONArray("words");
        for (int i = 0; i < list.length(); i++) { JSONObject word = list.getJSONObject(i); words.put(word.getString("id"), word); withdrawn.remove(word.getString("id")); }
        JSONArray removed = pack.optJSONArray("withdrawnWordIds");
        if (removed != null) for (int i = 0; i < removed.length(); i++) withdrawn.put(removed.getString(i), true);
        next.put("withdrawn", withdrawn).put("pack", pack);
        return true;
    }
    public synchronized JSONObject snapshot() { return copy(); }
    public synchronized JSONObject settings() { return field("settings"); }
    public synchronized JSONArray events() {
        try { return new JSONArray(state.getJSONArray("events").toString()); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized JSONObject words() {
        JSONObject words = field("words"), removed = state.optJSONObject("withdrawn");
        if (removed != null) removed.keys().forEachRemaining(words::remove);
        return words;
    }
    public synchronized JSONObject pack() {
        ensureCurrentPack(); return field("pack");
    }
    synchronized void ensureCurrentPack() {
        String today = LocalDate.now(StudyEngine.STUDY_ZONE).toString();
        if (today.equals(activatedDay)) return;
        try { JSONObject next = copy(); if (activatePackage(next, today)) write(next); activatedDay = today; }
        catch (Exception e) { throw new IllegalStateException("学习包切换未能保存", e); }
    }
    public synchronized JSONObject session() { return field("session"); }
    public synchronized String deviceId() { return state.optString("deviceId"); }
    public synchronized void saveSettings(JSONObject settings) throws Exception {
        JSONObject n = copy(); boolean changed = false;
        for (String key : new String[]{"owner", "repo", "branch"}) if (!n.getJSONObject("settings").optString(key).equals(settings.optString(key))) changed = true;
        if (changed && SyncCoordinator.running()) throw new IllegalStateException("同步中，请稍后保存仓库配置");
        if (changed) n.put("lastSyncedSeq", 0).put("acknowledgedEventIds", new JSONArray()).put("lastSync", 0).put("lastAttempt", 0);
        n.put("settings", new JSONObject(settings.toString())); write(n);
    }
    public synchronized int pendingCount() { return pendingCount(state); }
    static int pendingCount(JSONObject value) {
        Set<String> ack = new HashSet<>(); JSONArray ids = value.optJSONArray("acknowledgedEventIds");
        if (ids != null) for (int i = 0; i < ids.length(); i++) ack.add(ids.optString(i));
        int count = 0; JSONArray events = value.optJSONArray("events");
        if (events != null) for (int i = 0; i < events.length(); i++) { JSONObject e = events.optJSONObject(i);
            if (e != null && value.optString("deviceId").equals(e.optString("deviceId")) && e.optLong("seq") > value.optLong("lastSyncedSeq") && !ack.contains(e.optString("id"))) count++;
        }
        return count;
    }
    public synchronized void saveSession(JSONObject session) throws Exception {
        JSONObject n = LearningStorage.shallow(state);
        // A new frozen session is a content checkpoint, subsequent flips only write its progress.
        n.put("session", session == null ? JSONObject.NULL : new JSONObject(session.toString())); write(n);
    }
    synchronized JSONObject prepareSession() throws Exception {
        JSONObject old = state.optJSONObject("session");
        long now = System.currentTimeMillis(); String today = LocalDate.now(StudyEngine.STUDY_ZONE).toString();
        if (old == null || !today.equals(old.optString("date"))) return null;
        JSONObject pack = old.optJSONObject("pack");
        if (pack == null) return old.has("feedbackEvent") ? old : null;
        int limit = LearningSettings.newLimit(state.getJSONObject("settings"));
        if (eligible == null || eligibleEvents != state.opt("events") || eligibleWords != state.opt("words")
                || eligibleWithdrawn != state.opt("withdrawn") || eligiblePack != pack || eligibleLimit != limit || !today.equals(eligibleDay)
                || now < eligibleAt || now >= eligibleUntil) {
            eligible = StudyEngine.queue(pack, old.getJSONObject("words"), state.getJSONArray("events"), now, limit);
            eligibleEvents = state.opt("events"); eligibleWords = state.opt("words"); eligibleWithdrawn = state.opt("withdrawn");
            eligiblePack = pack; eligibleLimit = limit; eligibleDay = today;
            eligibleAt = now; eligibleUntil = Long.MAX_VALUE;
            JSONArray events = state.getJSONArray("events");
            for (int i = 0; i < events.length(); i++) {
                long at = events.getJSONObject(i).optLong("at"); if (at > now) eligibleUntil = Math.min(eligibleUntil, at);
            }
        }
        JSONObject available = LearningStorage.shallow(state.getJSONObject("words"));
        JSONObject removed = state.optJSONObject("withdrawn");
        if (removed != null) removed.keys().forEachRemaining(available::remove);
        JSONObject prepared = SessionPolicy.reconcilePrepared(old, available, now, eligible);
        if (prepared == null) return null;
        int position = prepared.optInt("position");
        if (position < prepared.getJSONArray("queue").length()) {
            JSONObject task = prepared.getJSONArray("queue").getJSONObject(position);
            boolean selfReview = task.optBoolean("review") && (pack.optInt("schemaVersion", 1) == 1 || "self-recall".equals(pack.optString("reviewMode")));
            if (!selfReview && !prepared.has("questionIndex")) {
                JSONObject word = prepared.getJSONObject("words").getJSONObject(task.getString("wordId"));
                JSONArray questions = word.getJSONArray("questions"); String requested = state.getJSONObject("settings").optString("practiceMode", "mixed");
                ArrayList<Integer> candidates = new ArrayList<>();
                for (int i = 0; i < questions.length(); i++) if (requested.equals("mixed") || requested.equals(questions.getJSONObject(i).getString("mode"))) candidates.add(i);
                if (candidates.isEmpty()) candidates.add(0);
                prepared.put("questionIndex", candidates.get(Math.floorMod((word.getString("id") + today).hashCode(), candidates.size())));
            }
        }
        if (!LearningStorage.sessionProgress(old).toString().equals(LearningStorage.sessionProgress(prepared).toString())) {
            writeProgress(LearningStorage.shallow(state).put("session", prepared));
            return prepared;
        }
        return old;
    }
    synchronized void editSession(String sessionId, String wordId, String key, boolean value) throws Exception {
        JSONObject current = prepareSession();
        if (current == null || !sessionId.equals(current.optString("sessionId")) || current.has("feedbackEvent")
                || current.optInt("position") >= current.getJSONArray("queue").length()
                || !wordId.equals(current.getJSONArray("queue").getJSONObject(current.getInt("position")).getString("wordId")))
            throw new IllegalStateException("题目已变化，请重新打开当前题");
        if (!(key.equals("revealed") || key.equals("assisted") || key.equals("inputEnabled"))) throw new IllegalArgumentException("Invalid session edit");
        JSONObject session = LearningStorage.shallow(current).put(key, value);
        if (key.equals("inputEnabled") && value && current.optBoolean("revealed")) session.put("assisted", true);
        writeProgress(LearningStorage.shallow(state).put("session", session));
    }
    synchronized void advance(String eventId) throws Exception {
        JSONObject old = state.optJSONObject("session");
        if (old == null || !old.has("feedbackEvent") || !eventId.equals(old.getJSONObject("feedbackEvent").getString("id")))
            throw new IllegalStateException("这次作答已变化，请重新打开");
        JSONObject session = LearningStorage.shallow(old);
        session.put("position", session.getInt("position") + 1); session.remove("feedbackEvent"); session.remove("questionIndex");
        session.put("revealed", false).put("assisted", false).put("inputEnabled", false);
        if (session.getInt("position") >= session.getJSONArray("queue").length()) session.put("completedAt", System.currentTimeMillis());
        writeProgress(LearningStorage.shallow(state).put("session", session));
    }
    public synchronized JSONObject answer(String wordId, String packageId, String mode, String result, String attempt, boolean assisted, boolean review) throws Exception {
        JSONObject checked = prepareSession();
        if (checked == null || checked.has("feedbackEvent") || checked.optInt("position") >= checked.getJSONArray("queue").length()
                || !wordId.equals(checked.getJSONArray("queue").getJSONObject(checked.getInt("position")).getString("wordId")))
            throw new IllegalStateException("题目已随最新学习进度变化，请重新打开");
        JSONObject next = LearningStorage.shallow(state); long seq = next.optLong("seq") + 1;
        next.put("events", arrayCopy(state.getJSONArray("events")));
        next.put("session", LearningStorage.shallow(checked));
        JSONObject event = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId()).put("seq", seq)
            .put("at", System.currentTimeMillis()).put("wordId", wordId).put("packageId", packageId).put("type", "answer")
            .put("mode", mode).put("result", result).put("attempt", attempt).put("assisted", assisted).put("isReview", review);
        next.getJSONArray("events").put(event); next.put("seq", seq);
        JSONObject session = next.optJSONObject("session");
        if (session != null) {
            event.put("sessionId", session.optString("sessionId", "legacy"));
            event.put("packageRevision", session.optInt("packageRevision"));
            JSONObject frozenWord = session.getJSONObject("words").optJSONObject(wordId);
            int index = session.optInt("questionIndex", -1);
            if (index >= 0 && frozenWord != null) event.put("questionId", frozenWord.getJSONArray("questions").getJSONObject(index).getString("id"));
            boolean selfReport = result.equals("remember") || result.equals("hard") || result.equals("forgot");
            event.put("evidence", selfReport ? "self-report" : mode.equals("skip") ? "known-skip" : assisted ? "assisted" : "independent-choice");
            if (frozenWord != null && index >= 0) {
                String type = frozenWord.getJSONArray("questions").getJSONObject(index).optString("type", "choice");
                event.put("evidence", selfReport ? "self-report" : result.equals("known") ? "known-skip" : assisted ? "assisted" : type.equals("input") ? "independent-input" : "independent-choice");
            }
            JSONObject policy = session.optJSONObject("pack") == null ? null : session.getJSONObject("pack").optJSONObject("reviewPolicy");
            if (policy != null) {
                JSONArray days = policy.getJSONArray("intervalDays");
                StudyEngine.Card card = StudyEngine.project(state.getJSONArray("events"), event.getLong("at")).get(wordId);
                int stage = card == null || card.dueAt == 0 ? 0 : card.dueAt <= event.getLong("at") && !assisted ? card.stage + 1 : card.stage;
                int interval = result.equals("hard") ? policy.optInt("hardAfterDays", 1)
                    : result.equals("wrong") || result.equals("forgot") ? policy.optInt("wrongAfterDays", 1)
                    : days.getInt(Math.max(0, Math.min(stage, days.length() - 1)));
                event.put("scheduleDays", interval);
            }
            session.put("feedbackEvent", event);
        }
        writeProgress(next); return new JSONObject(event.toString());
    }
    public synchronized void undo(String targetId) throws Exception {
        JSONObject n = LearningStorage.shallow(state); long seq = n.optLong("seq") + 1;
        n.put("events", arrayCopy(state.getJSONArray("events")));
        if (state.optJSONObject("session") != null) n.put("session", LearningStorage.shallow(state.getJSONObject("session")));
        n.getJSONArray("events").put(new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId())
            .put("seq", seq).put("at", System.currentTimeMillis()).put("type", "undo").put("targetEventId", targetId));
        n.put("seq", seq); JSONObject session = n.optJSONObject("session");
        if (session != null) { session.remove("feedbackEvent"); session.put("assisted", true); }
        writeProgress(n);
    }
    public synchronized void acceptSync(JSONObject pack, JSONArray events) throws Exception {
        Set<String> ack = new HashSet<>();
        for (int i = 0; i < events.length(); i++) if (deviceId().equals(events.getJSONObject(i).optString("deviceId"))) ack.add(events.getJSONObject(i).getString("id"));
        acceptSync(pack, events, ack, "");
    }
    public synchronized void acceptSync(JSONObject pack, JSONArray events, Set<String> acknowledged, String warning) throws Exception {
        acceptSyncPackages(pack == null ? Collections.emptyList() : Collections.singletonList(pack), events, acknowledged, warning);
    }
    public synchronized void acceptSyncPackages(java.util.List<JSONObject> packs, JSONArray events, Set<String> acknowledged, String warning) throws Exception {
        JSONObject next = copy(); next.put("events", StudyEngine.mergeEvents(next.getJSONArray("events"), events));
        for (int i = 0; i < events.length(); i++) if (deviceId().equals(events.getJSONObject(i).optString("deviceId"))) next.put("seq", Math.max(next.optLong("seq"), events.getJSONObject(i).optLong("seq")));
        acknowledge(next, acknowledged);
        String packError = warning == null ? "" : warning;
        for (JSONObject pack : packs) {
            try { insertPackage(next, pack); }
            catch (Exception incompatible) { packError = "新词包未应用：版本、基线或内容不兼容。已保留原包，学习记录仍可同步。"; }
        }
        next.put("packError", packError).put("lastPartialSync", System.currentTimeMillis()).put("syncError", "");
        if (pendingCount(next) == 0) next.put("lastSync", System.currentTimeMillis());
        write(next); activatedDay = "";
    }
    static void acknowledge(JSONObject next, Set<String> confirmed) throws Exception {
        Set<String> ack = new HashSet<>(confirmed); JSONArray old = next.optJSONArray("acknowledgedEventIds");
        if (old != null) for (int i = 0; i < old.length(); i++) ack.add(old.getString(i));
        java.util.TreeMap<Long, String> own = new java.util.TreeMap<>(); JSONArray all = next.getJSONArray("events");
        for (int i = 0; i < all.length(); i++) { JSONObject e = all.getJSONObject(i);
            if (next.optString("deviceId").equals(e.optString("deviceId"))) own.put(e.getLong("seq"), e.getString("id"));
        }
        long continuous = next.optLong("lastSyncedSeq");
        while (own.containsKey(continuous + 1) && ack.contains(own.get(continuous + 1))) continuous++;
        JSONArray remaining = new JSONArray();
        for (java.util.Map.Entry<Long, String> e : own.entrySet()) if (e.getKey() > continuous && ack.contains(e.getValue())) remaining.put(e.getValue());
        next.put("lastSyncedSeq", continuous).put("acknowledgedEventIds", remaining);
    }
    public synchronized void syncAttempt(String error) throws Exception {
        JSONObject next = LearningStorage.shallow(state); next.put("lastAttempt", System.currentTimeMillis());
        if (error != null) next.put("syncError", error); writeProgress(next);
    }
}
