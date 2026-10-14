package cn.wordwatch.app;

import cn.wordwatch.app.core.LearningSettings;
import cn.wordwatch.app.core.SessionPolicy;
import cn.wordwatch.app.core.StudyEngine;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Read-only screen data, prepared on the study worker. Rendering never reads the store or disk. */
final class StudyScreen {
    String screen;
    JSONObject state, settings, pack, session, words;
    List<StudyEngine.Task> queue = Collections.emptyList();
    List<JSONObject> entries = Collections.emptyList();
    Map<String, StudyEngine.Card> cards = Collections.emptyMap();
    int[] counts = new int[3];
    int pending, reviews;

    static StudyScreen prepare(AppStore store, String requested) throws Exception {
        synchronized (store) {
            store.ensureCurrentPack();
            StudyScreen model = new StudyScreen(); model.screen = requested;
            model.session = requested.equals("question") || requested.equals("home") ? store.prepareSession() : store.readView().optJSONObject("session");
            if (requested.equals("question") && (model.session == null || model.session.optInt("position") >= model.session.getJSONArray("queue").length()))
                model.screen = "done";
            model.state = store.readView(); model.settings = model.state.getJSONObject("settings"); model.pack = model.state.optJSONObject("pack");
            model.words = LearningStorage.shallow(model.state.getJSONObject("words"));
            JSONObject removed = model.state.optJSONObject("withdrawn");
            if (removed != null) removed.keys().forEachRemaining(model.words::remove);
            JSONArray events = model.state.getJSONArray("events"); long now = System.currentTimeMillis();
            if (model.screen.equals("home") || model.screen.equals("done")) {
                model.counts = StudyEngine.todayCounts(events, now); model.pending = AppStore.pendingCount(model.state);
            }
            if (model.screen.equals("home") && model.pack != null) {
                model.queue = StudyEngine.queue(model.pack, model.words, events, now, LearningSettings.newLimit(model.settings));
                for (StudyEngine.Task task : model.queue) if (task.review) model.reviews++;
            }
            if (model.screen.equals("words")) {
                model.cards = StudyEngine.project(events, now); model.entries = new ArrayList<>();
                for (java.util.Iterator<String> keys = model.words.keys(); keys.hasNext();) model.entries.add(model.words.getJSONObject(keys.next()));
                model.entries.sort((a, b) -> Integer.compare(a.optInt("rank", Integer.MAX_VALUE), b.optInt("rank", Integer.MAX_VALUE)));
            }
            return model;
        }
    }
    static String start(AppStore store) throws Exception {
        synchronized (store) {
            store.ensureCurrentPack(); JSONObject session = store.prepareSession();
            if (session == null || session.optInt("position") >= session.getJSONArray("queue").length()) {
                JSONObject state = store.readView(), pack = state.optJSONObject("pack");
                if (pack == null) return "settings";
                JSONObject available = LearningStorage.shallow(state.getJSONObject("words"));
                JSONObject removed = state.optJSONObject("withdrawn"); if (removed != null) removed.keys().forEachRemaining(available::remove);
                List<StudyEngine.Task> tasks = StudyEngine.queue(pack, available, state.getJSONArray("events"), System.currentTimeMillis(), LearningSettings.newLimit(state.getJSONObject("settings")));
                JSONArray queue = new JSONArray(); JSONObject frozen = new JSONObject();
                for (int i = 0; i < pack.getJSONArray("words").length(); i++) {
                    JSONObject word = pack.getJSONArray("words").getJSONObject(i); frozen.put(word.getString("id"), word);
                }
                for (StudyEngine.Task task : tasks) {
                    if (queue.length() >= SessionPolicy.MAX_SESSION_TASKS) break;
                    queue.put(new JSONObject().put("wordId", task.wordId).put("review", task.review).put("attempt", task.attempt));
                    if (available.has(task.wordId)) frozen.put(task.wordId, available.getJSONObject(task.wordId));
                }
                session = new JSONObject().put("queue", queue).put("position", 0).put("date", LocalDate.now(StudyEngine.STUDY_ZONE).toString())
                        .put("sessionId", UUID.randomUUID().toString()).put("packageRevision", StudyEngine.revision(pack)).put("pack", pack)
                        .put("packageId", pack.getString("packageId")).put("words", frozen).put("assisted", false).put("revealed", false);
                store.saveSession(session);
            }
            return session.getJSONArray("queue").length() == 0 ? "done" : "question";
        }
    }
}
