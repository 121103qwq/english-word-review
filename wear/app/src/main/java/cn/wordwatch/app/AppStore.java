package cn.wordwatch.app;

import android.content.Context;
import android.util.AtomicFile;
import cn.wordwatch.app.core.StudyEngine;
import cn.wordwatch.app.core.JsonCopies;
import cn.wordwatch.app.core.SessionPolicy;
import cn.wordwatch.app.core.LearningSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
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
    private final AtomicFile file;
    private final AppStoreJsonSerializer jsonSerializer = new AppStoreJsonSerializer();
    private JSONObject state;
    public static synchronized AppStore get(Context context) {
        if (instance == null) instance = new AppStore(context.getApplicationContext());
        return instance;
    }
    private AppStore(Context context) {
        file = new AtomicFile(new File(context.getFilesDir(), "learning-state.json"));
        try {
            if (file.getBaseFile().exists()) state = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            else {
                state = new JSONObject().put("schemaVersion", 1).put("deviceId", UUID.randomUUID().toString()).put("seq", 0)
                    .put("events", new JSONArray()).put("words", new JSONObject()).put("settings", new JSONObject()
                    .put("owner", "121103qwq").put("repo", "english-word-review-data").put("branch", "main").put("newLimit", StudyEngine.DEFAULT_NEW_WORD_LIMIT));
                write(state);
            }
            if (upgradeDailyLimit(state)) write(state);
            if (!state.getJSONObject("settings").has("limitMode")) {
                state.getJSONObject("settings").put("limitMode", "package"); write(state);
            }
            if (state.optJSONObject("pack") == null) {
                try (InputStream input = context.getAssets().open("bootstrap.json")) {
                    JSONObject pack = new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
                    if (pack.has("packageId")) {
                        StudyEngine.validatePackage(pack);
                        // Bootstrap insertion mutates the initial root's content branches in place.
                        jsonSerializer.invalidate();
                        insertPackage(state, pack);
                    }
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
    private JSONObject copy() { return JsonCopies.object(state); }
    private JSONObject copyRoot() {
        try {
            JSONObject next = new JSONObject();
            for (java.util.Iterator<String> keys = state.keys(); keys.hasNext();) {
                String key = keys.next(); next.put(key, state.get(key));
            }
            return next;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static JSONObject copyObject(JSONObject value) {
        if (value == null) return null;
        return JsonCopies.object(value);
    }
    private static JSONObject copyObjectShallow(JSONObject value) {
        if (value == null) return null;
        try {
            JSONObject next = new JSONObject();
            for (java.util.Iterator<String> keys = value.keys(); keys.hasNext();) {
                String key = keys.next(); next.put(key, value.get(key));
            }
            return next;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    private static JSONArray copyArray(JSONArray value) {
        if (value == null) return null;
        return JsonCopies.array(value);
    }
    private static JSONArray copyArrayShallow(JSONArray value) {
        if (value == null) return null;
        JSONArray next = new JSONArray();
        try { for (int i = 0; i < value.length(); i++) next.put(value.get(i)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        return next;
    }
    private void write(JSONObject next) throws Exception {
        FileOutputStream stream = null;
        AppStoreJsonSerializer.Prepared prepared = null;
        long serializeMs = 0, commitMs = 0, commitStarted = -1;
        try {
            long started = System.nanoTime();
            try { prepared = jsonSerializer.prepare(next); }
            finally { serializeMs = (System.nanoTime() - started) / 1_000_000L; }

            commitStarted = System.nanoTime();
            stream = file.startWrite();
            stream.write(prepared.bytes);
            file.finishWrite(stream);
            stream = null;
            commitMs = (System.nanoTime() - commitStarted) / 1_000_000L;
            state = next;
            jsonSerializer.committed(prepared);
        }
        catch (Exception e) {
            if (stream != null) try { file.failWrite(stream); } catch (Exception rollback) { e.addSuppressed(rollback); }
            throw e;
        }
        finally {
            if (commitStarted >= 0 && commitMs == 0) commitMs = (System.nanoTime() - commitStarted) / 1_000_000L;
            android.util.Log.i("WordWatchPerformance", "store.write serializeMs=" + serializeMs + " commitMs=" + commitMs
                    + " chunkCacheHits=" + (prepared == null ? 0 : prepared.cacheHits));
        }
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
        JSONObject pack = selectedPackage(next, today); if (pack == null) return false;
        JSONObject old = next.optJSONObject("pack");
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
    private static JSONObject selectedPackage(JSONObject source, String today) {
        JSONObject packs = source.optJSONObject("packages"); if (packs == null) return null;
        ArrayList<String> dates = new ArrayList<>(); packs.keys().forEachRemaining(dates::add); Collections.sort(dates);
        String selected = null;
        for (String date : dates) if (date.compareTo(today) <= 0) selected = date;
        // A future-only pack is visible as downloaded, but StudyEngine gates its new candidates by date.
        if (selected == null && !dates.isEmpty()) selected = dates.get(0);
        return selected == null ? null : packs.optJSONObject(selected);
    }
    public synchronized JSONObject snapshot() { return copy(); }
    public synchronized JSONObject settings() { return copyObject(state.optJSONObject("settings")); }
    public synchronized JSONArray events() { return copyArray(state.optJSONArray("events")); }
    public synchronized JSONObject words() {
        JSONObject words = copyObject(state.optJSONObject("words")), removed = state.optJSONObject("withdrawn");
        if (words == null) return null;
        if (removed != null) removed.keys().forEachRemaining(words::remove);
        return words;
    }
    public synchronized JSONObject pack() {
        String today = LocalDate.now(StudyEngine.STUDY_ZONE).toString();
        JSONObject selected = selectedPackage(state, today), current = state.optJSONObject("pack");
        try {
            if (selected != null && (current == null || !current.toString().equals(selected.toString()))) {
                JSONObject next = copyRoot();
                JSONObject packages = copyObjectShallow(state.optJSONObject("packages"));
                if (packages != null) next.put("packages", packages);
                JSONObject words = copyObjectShallow(state.optJSONObject("words"));
                if (words != null) next.put("words", words);
                JSONObject withdrawn = copyObjectShallow(state.optJSONObject("withdrawn"));
                if (withdrawn != null) next.put("withdrawn", withdrawn);
                if (activatePackage(next, today)) write(next);
            }
        }
        catch (Exception e) { throw new IllegalStateException("学习包切换未能保存", e); }
        return copyObject(state.optJSONObject("pack"));
    }
    public synchronized JSONObject session() { return copyObject(state.optJSONObject("session")); }
    public synchronized String deviceId() { return state.optString("deviceId"); }
    public synchronized void saveSettings(JSONObject settings) throws Exception {
        JSONObject n = copyRoot(); boolean changed = false;
        boolean studyLimitChanged = LearningSettings.newLimit(n.optJSONObject("settings"))
                != LearningSettings.newLimit(settings);
        for (String key : new String[]{"owner", "repo", "branch"}) if (!n.getJSONObject("settings").optString(key).equals(settings.optString(key))) changed = true;
        if (changed && SyncCoordinator.running()) throw new IllegalStateException("同步中，请稍后保存仓库配置");
        if (changed) n.put("lastSyncedSeq", 0).put("acknowledgedEventIds", new JSONArray()).put("lastSync", 0).put("lastAttempt", 0);
        if (studyLimitChanged) n.remove("gestureUndo");
        n.put("settings", copyObject(settings)); write(n);
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
        JSONObject nextSession = copyObject(session), n = copyRoot();
        n.put("session", nextSession == null ? JSONObject.NULL : nextSession);
        JSONObject checkpoint = state.optJSONObject("gestureUndo");
        if (checkpoint != null && !gestureCheckpointMatchesSession(checkpoint, nextSession)) n.remove("gestureUndo");
        write(n);
    }
    private JSONObject availableWordsForPolicy() throws Exception {
        JSONObject available = new JSONObject(), words = state.optJSONObject("words"), withdrawn = state.optJSONObject("withdrawn");
        if (words == null) return available;
        for (java.util.Iterator<String> keys = words.keys(); keys.hasNext();) {
            String id = keys.next();
            if (withdrawn == null || !withdrawn.has(id)) available.put(id, words.get(id));
        }
        return available;
    }
    private JSONObject reconcileCurrentSession(long now) throws Exception {
        return SessionPolicy.reconcile(state.optJSONObject("session"), state.optJSONArray("events"),
                availableWordsForPolicy(), now, LearningSettings.newLimit(state.optJSONObject("settings")));
    }
    private static JSONObject requireGestureTask(JSONObject session, String sessionId, String wordId, int position) throws Exception {
        if (session == null || sessionId == null || wordId == null
                || !sessionId.equals(session.optString("sessionId"))
                || session.optInt("position", -1) != position || position < 0
                || session.has("feedbackEvent")) {
            throw new IllegalStateException("学习进度已变化，请重新打开当前词");
        }
        JSONArray queue = session.optJSONArray("queue");
        if (queue == null || position >= queue.length()) throw new IllegalStateException("当前学习已完成");
        JSONObject task = queue.getJSONObject(position);
        if (!wordId.equals(task.optString("wordId"))) throw new IllegalStateException("当前词已变化，请重新打开");
        JSONObject frozen = session.optJSONObject("words");
        if (frozen == null || !frozen.has(wordId)) throw new IllegalStateException("当前词内容已变化，请重新打开");
        return task;
    }
    private static String currentWordAt(JSONObject session, int position) {
        JSONArray queue = session == null ? null : session.optJSONArray("queue");
        JSONObject task = queue == null || position < 0 || position >= queue.length() ? null : queue.optJSONObject(position);
        return task == null ? "" : task.optString("wordId");
    }
    private static boolean gestureCheckpointMatchesSession(JSONObject checkpoint, JSONObject session) {
        if (checkpoint == null || session == null
                || !checkpoint.optString("sessionId").equals(session.optString("sessionId"))
                || checkpoint.optInt("nextPosition", -1) != session.optInt("position", -2)
                || checkpoint.optInt("position", -1) + 1 != session.optInt("position", -2)
                || session.has("feedbackEvent")) return false;
        return checkpoint.optString("wordId").equals(currentWordAt(session, checkpoint.optInt("position", -1)))
                && checkpoint.optString("nextWordId").equals(currentWordAt(session, session.optInt("position", -1)));
    }
    public synchronized boolean hasGestureUndo() {
        JSONObject checkpoint = state.optJSONObject("gestureUndo"), session = state.optJSONObject("session");
        return checkpoint != null && checkpoint.optLong("seq", -1) == state.optLong("seq", -2)
                && gestureCheckpointMatchesSession(checkpoint, session)
                && LocalDate.now(StudyEngine.STUDY_ZONE).toString().equals(session.optString("date"));
    }
    public synchronized JSONObject revealForGesture(String sessionId, String wordId, int position) throws Exception {
        JSONObject checked = reconcileCurrentSession(System.currentTimeMillis());
        requireGestureTask(checked, sessionId, wordId, position);
        if (checked.optBoolean("revealed")) return copyObject(checked);
        JSONObject updated = copyObjectShallow(checked).put("revealed", true);
        JSONObject next = copyRoot(); next.put("session", updated); write(next);
        return copyObject(updated);
    }
    private JSONObject appendAnswerEvent(JSONObject base, JSONObject next, JSONObject session,
            String wordId, String packageId, String mode, String result, String attempt,
            boolean assisted, boolean review, long at) throws Exception {
        long seq = next.optLong("seq") + 1;
        JSONObject event = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId()).put("seq", seq)
            .put("at", at).put("wordId", wordId).put("packageId", packageId).put("type", "answer")
            .put("mode", mode).put("result", result).put("attempt", attempt).put("assisted", assisted).put("isReview", review);
        if (session != null) {
            event.put("sessionId", session.optString("sessionId", "legacy"));
            event.put("packageRevision", session.optInt("packageRevision"));
            JSONObject frozen = session.optJSONObject("words");
            JSONObject frozenWord = frozen == null ? null : frozen.optJSONObject(wordId);
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
                StudyEngine.Card card = StudyEngine.project(base.getJSONArray("events"), at).get(wordId);
                int stage = card == null || card.dueAt == 0 ? 0 : card.dueAt <= at && !assisted ? card.stage + 1 : card.stage;
                int interval = result.equals("hard") ? policy.optInt("hardAfterDays", 1)
                    : result.equals("wrong") || result.equals("forgot") ? policy.optInt("wrongAfterDays", 1)
                    : days.getInt(Math.max(0, Math.min(stage, days.length() - 1)));
                event.put("scheduleDays", interval);
            }
        }
        next.getJSONArray("events").put(event); next.put("seq", seq);
        return event;
    }
    public synchronized JSONObject answer(String wordId, String packageId, String mode, String result, String attempt, boolean assisted, boolean review) throws Exception {
        long now = System.currentTimeMillis();
        JSONObject checked = SessionPolicy.reconcile(state.optJSONObject("session"), state.optJSONArray("events"),
                availableWordsForPolicy(), now, LearningSettings.newLimit(state.optJSONObject("settings")));
        if (checked == null || checked.has("feedbackEvent") || checked.optInt("position") >= checked.getJSONArray("queue").length()
                || !wordId.equals(checked.getJSONArray("queue").getJSONObject(checked.getInt("position")).getString("wordId")))
            throw new IllegalStateException("题目已随最新学习进度变化，请重新打开");
        JSONObject next = copyRoot(); next.put("events", copyArrayShallow(state.optJSONArray("events")));
        JSONObject session = copyObjectShallow(checked); if (session != null) next.put("session", session);
        JSONObject event = appendAnswerEvent(state, next, session, wordId, packageId, mode, result, attempt, assisted, review, now);
        if (session != null) session.put("feedbackEvent", event);
        next.remove("gestureUndo");
        write(next); return copyObject(event);
    }
    public synchronized JSONObject answerAndAdvanceGesture(String sessionId, String wordId, int position, String result) throws Exception {
        if (!"remember".equals(result) && !"hard".equals(result))
            throw new IllegalArgumentException("手势作答只接受记得或模糊");
        long now = System.currentTimeMillis();
        JSONObject checked = reconcileCurrentSession(now);
        JSONObject task = requireGestureTask(checked, sessionId, wordId, position);
        if (!checked.optBoolean("revealed") || checked.optBoolean("inputEnabled"))
            throw new IllegalStateException("请先翻开当前词，再提交手势自评");
        JSONObject eventSession = copyObjectShallow(checked);
        String mode = gestureAnswerMode(checked, task, wordId);
        JSONObject next = copyRoot(); next.put("events", copyArrayShallow(state.optJSONArray("events")));
        JSONObject event = appendAnswerEvent(state, next, eventSession, wordId, checked.getString("packageId"), mode,
                result, task.optString("attempt", "primary"), checked.optBoolean("assisted"), task.optBoolean("review"), now);

        JSONObject advanced = copyObjectShallow(checked);
        int nextPosition = position + 1;
        advanced.put("position", nextPosition).put("revealed", false).put("assisted", false).put("inputEnabled", false);
        advanced.remove("feedbackEvent"); advanced.remove("questionIndex"); advanced.remove("completedAt");
        if (nextPosition >= checked.getJSONArray("queue").length()) advanced.put("completedAt", now);
        JSONObject updated = SessionPolicy.reconcile(advanced, next.getJSONArray("events"), availableWordsForPolicy(),
                now, LearningSettings.newLimit(state.optJSONObject("settings")));
        if (updated == null || updated.optInt("position", -1) != nextPosition)
            throw new IllegalStateException("学习队列已变化，请重新打开");
        JSONObject checkpoint = new JSONObject()
                .put("eventId", event.getString("id"))
                .put("sessionId", sessionId)
                .put("wordId", wordId)
                .put("position", position)
                .put("nextPosition", nextPosition)
                .put("nextWordId", currentWordAt(updated, nextPosition))
                .put("seq", next.optLong("seq"))
                .put("questionIndex", checked.optInt("questionIndex", -1));
        next.put("session", updated).put("gestureUndo", checkpoint);
        write(next);
        return copyObject(updated);
    }
    private static String gestureAnswerMode(JSONObject session, JSONObject task, String wordId) throws Exception {
        JSONObject pack = session.optJSONObject("pack");
        boolean selfReview = task.optBoolean("review") && (pack == null || pack.optInt("schemaVersion", 1) == 1
                || "self-recall".equals(pack.optString("reviewMode")));
        if (selfReview) return "recall";
        JSONObject frozen = session.getJSONObject("words").optJSONObject(wordId);
        JSONArray questions = frozen == null ? null : frozen.optJSONArray("questions");
        int index = session.optInt("questionIndex", -1);
        if (questions != null && index >= 0 && index < questions.length()) return questions.getJSONObject(index).optString("mode", "recall");
        return "recall";
    }
    public synchronized JSONObject undoLastGestureAnswer() throws Exception {
        JSONObject checkpoint = state.optJSONObject("gestureUndo"), current = state.optJSONObject("session");
        if (checkpoint == null || checkpoint.optLong("seq", -1) != state.optLong("seq", -2)
                || !gestureCheckpointMatchesSession(checkpoint, current)
                || !LocalDate.now(StudyEngine.STUDY_ZONE).toString().equals(current.optString("date"))
                || !hasActiveGestureAnswer(state.optJSONArray("events"), checkpoint, state.optString("deviceId")))
            throw new IllegalStateException("这次手势作答已失效，无法撤销");

        long now = System.currentTimeMillis();
        JSONObject restored = copyObjectShallow(current);
        if (restored == null) throw new IllegalStateException("撤销记录不完整");
        restored.remove("feedbackEvent"); restored.remove("completedAt");
        restored.put("position", checkpoint.getInt("position"));
        int questionIndex = checkpoint.optInt("questionIndex", -1);
        if (questionIndex >= 0) restored.put("questionIndex", questionIndex); else restored.remove("questionIndex");
        restored.put("revealed", true).put("assisted", true).put("inputEnabled", false);

        JSONObject next = copyRoot(); next.put("events", copyArrayShallow(state.optJSONArray("events")));
        long seq = next.optLong("seq") + 1;
        next.getJSONArray("events").put(new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId())
                .put("seq", seq).put("at", now).put("type", "undo").put("targetEventId", checkpoint.getString("eventId")));
        next.put("seq", seq).remove("gestureUndo");
        JSONObject updated = SessionPolicy.reconcile(restored, next.getJSONArray("events"), availableWordsForPolicy(),
                now, LearningSettings.newLimit(state.optJSONObject("settings")));
        if (updated == null || !checkpoint.optString("wordId").equals(currentWordAt(updated, checkpoint.getInt("position"))))
            throw new IllegalStateException("当前词已不在今日学习队列，无法恢复");
        updated.put("revealed", true).put("assisted", true).put("inputEnabled", false);
        next.put("session", updated);
        write(next);
        return copyObject(updated);
    }
    private static boolean hasActiveGestureAnswer(JSONArray events, JSONObject checkpoint, String deviceId) {
        if (events == null) return false;
        String eventId = checkpoint.optString("eventId");
        boolean answerFound = false;
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i); if (event == null) continue;
            if ("undo".equals(event.optString("type")) && eventId.equals(event.optString("targetEventId"))) return false;
            if ("answer".equals(event.optString("type")) && eventId.equals(event.optString("id"))) {
                answerFound = deviceId.equals(event.optString("deviceId"))
                        && checkpoint.optString("sessionId").equals(event.optString("sessionId"))
                        && checkpoint.optString("wordId").equals(event.optString("wordId"))
                        && ("remember".equals(event.optString("result")) || "hard".equals(event.optString("result")));
            }
        }
        return answerFound;
    }
    public synchronized void undo(String targetId) throws Exception {
        JSONObject n = copyRoot(); n.put("events", copyArrayShallow(state.optJSONArray("events")));
        long seq = n.optLong("seq") + 1;
        n.getJSONArray("events").put(new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId())
            .put("seq", seq).put("at", System.currentTimeMillis()).put("type", "undo").put("targetEventId", targetId));
        n.put("seq", seq); JSONObject session = copyObjectShallow(state.optJSONObject("session"));
        if (session != null) { session.remove("feedbackEvent"); session.put("assisted", true); n.put("session", session); }
        n.remove("gestureUndo");
        write(n);
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
        next.remove("gestureUndo");
        write(next);
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
        JSONObject next = copyRoot(); next.put("lastAttempt", System.currentTimeMillis());
        if (error != null) next.put("syncError", error); write(next);
    }
}
