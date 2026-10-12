package cn.wordwatch.app;

import android.content.Context;
import android.util.AtomicFile;
import cn.wordwatch.app.core.StudyEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class AppStore {
    private static AppStore instance;
    private final AtomicFile file;
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
                    .put("owner", "121103qwq").put("repo", "english-word-review-data").put("branch", "main").put("newLimit", 15));
                write(state);
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
    private static byte[] readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray();
    }
    private JSONObject copy() { try { return new JSONObject(state.toString()); } catch (Exception e) { throw new IllegalStateException(e); } }
    private void write(JSONObject next) throws Exception {
        FileOutputStream stream = null;
        try { stream = file.startWrite(); stream.write(next.toString().getBytes(StandardCharsets.UTF_8)); file.finishWrite(stream); state = next; }
        catch (Exception e) { if (stream != null) file.failWrite(stream); throw e; }
    }
    private static void insertPackage(JSONObject next, JSONObject pack) throws Exception {
        JSONObject existing = next.optJSONObject("pack");
        if (existing != null && pack.getString("date").compareTo(existing.getString("date")) < 0) return;
        JSONObject words = next.getJSONObject("words"); JSONArray list = pack.getJSONArray("words");
        for (int i = 0; i < list.length(); i++) { JSONObject word = list.getJSONObject(i); words.put(word.getString("id"), word); }
        next.put("pack", pack);
    }
    public synchronized JSONObject snapshot() { return copy(); }
    public synchronized JSONObject settings() { return copy().optJSONObject("settings"); }
    public synchronized JSONArray events() { return copy().optJSONArray("events"); }
    public synchronized JSONObject words() { return copy().optJSONObject("words"); }
    public synchronized JSONObject pack() { return copy().optJSONObject("pack"); }
    public synchronized JSONObject session() { return copy().optJSONObject("session"); }
    public synchronized String deviceId() { return state.optString("deviceId"); }
    public synchronized void saveSettings(JSONObject settings) throws Exception { JSONObject n = copy(); n.put("settings", settings); write(n); }
    public synchronized void saveSession(JSONObject session) throws Exception { JSONObject n = copy(); n.put("session", session == null ? JSONObject.NULL : session); write(n); }
    public synchronized JSONObject answer(String wordId, String packageId, String mode, String result, String attempt, boolean assisted, boolean review) throws Exception {
        JSONObject next = copy(); long seq = next.optLong("seq") + 1;
        JSONObject event = new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId()).put("seq", seq)
            .put("at", System.currentTimeMillis()).put("wordId", wordId).put("packageId", packageId).put("type", "answer")
            .put("mode", mode).put("result", result).put("attempt", attempt).put("assisted", assisted).put("isReview", review);
        next.getJSONArray("events").put(event); next.put("seq", seq);
        JSONObject session = next.optJSONObject("session");
        if (session != null) session.put("feedbackEvent", event);
        write(next); return event;
    }
    public synchronized void undo(String targetId) throws Exception {
        JSONObject n = copy(); long seq = n.optLong("seq") + 1;
        n.getJSONArray("events").put(new JSONObject().put("id", UUID.randomUUID().toString()).put("deviceId", deviceId())
            .put("seq", seq).put("at", System.currentTimeMillis()).put("type", "undo").put("targetEventId", targetId));
        n.put("seq", seq); JSONObject session = n.optJSONObject("session");
        if (session != null) session.remove("feedbackEvent");
        write(n);
    }
    public synchronized void acceptSync(JSONObject pack, JSONArray events) throws Exception {
        StudyEngine.validatePackage(pack); JSONObject next = copy();
        next.put("events", StudyEngine.mergeEvents(next.getJSONArray("events"), events)); insertPackage(next, pack);
        long syncedSeq = next.optLong("lastSyncedSeq");
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (deviceId().equals(event.optString("deviceId"))) syncedSeq = Math.max(syncedSeq, event.optLong("seq"));
        }
        next.put("lastSyncedSeq", syncedSeq);
        next.put("lastSync", System.currentTimeMillis()).put("syncError", ""); write(next);
    }
    public synchronized void syncAttempt(String error) throws Exception {
        JSONObject next = copy(); next.put("lastAttempt", System.currentTimeMillis());
        if (error != null) next.put("syncError", error); write(next);
    }
}
