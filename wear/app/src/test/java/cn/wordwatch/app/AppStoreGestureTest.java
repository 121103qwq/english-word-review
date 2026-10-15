package cn.wordwatch.app;

import android.content.Context;
import android.util.AtomicFile;

import cn.wordwatch.app.core.StudyEngine;
import cn.wordwatch.app.render.ShadowAtomicFile;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35, shadows = ShadowAtomicFile.class)
public final class AppStoreGestureTest {
    private Context context;
    private AppStore store;
    private JSONObject session;
    private String firstWordId;

    @Before public void setUp() throws Exception {
        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        context = RuntimeEnvironment.getApplication();
        File base = new File(context.getFilesDir(), "learning-state.json");
        JSONObject initial = new JSONObject().put("schemaVersion", 1).put("deviceId", UUID.randomUUID().toString())
                .put("seq", 0).put("events", new JSONArray()).put("words", new JSONObject())
                .put("settings", new JSONObject().put("owner", "test").put("repo", "synthetic")
                        .put("branch", "main").put("newLimit", 10).put("newLimitDefaultVersion", 2)
                        .put("limitMode", "package"));
        try (FileOutputStream output = new FileOutputStream(base)) {
            output.write(initial.toString().getBytes(StandardCharsets.UTF_8));
        }
        store = AppStore.get(context);

        String today = LocalDate.now(StudyEngine.STUDY_ZONE).toString();
        JSONObject pack = packageFor(today);
        store.acceptSyncPackages(Collections.singletonList(pack), new JSONArray(), Collections.emptySet(), "");
        JSONObject activePack = store.pack();
        JSONObject frozen = new JSONObject(); JSONArray queue = new JSONArray();
        JSONArray words = activePack.getJSONArray("words");
        for (int i = 0; i < words.length(); i++) {
            JSONObject word = words.getJSONObject(i);
            frozen.put(word.getString("id"), word);
            queue.put(new JSONObject().put("wordId", word.getString("id")).put("review", false).put("attempt", "primary"));
        }
        firstWordId = queue.getJSONObject(0).getString("wordId");
        session = new JSONObject().put("queue", queue).put("position", 0).put("date", today)
                .put("sessionId", UUID.randomUUID().toString()).put("packageRevision", 0)
                .put("pack", activePack).put("packageId", activePack.getString("packageId"))
                .put("words", frozen).put("questionIndex", 0).put("assisted", false)
                .put("revealed", false).put("inputEnabled", false);
        store.saveSession(session);
    }

    @Test public void gettersReturnOnlyDetachedSubtrees() throws Exception {
        JSONObject before = store.snapshot();
        // saveSession must detach caller-owned input too, so later test mutations cannot
        // contaminate the committed branch through a retained reference.
        session.put("position", 99);
        Field stateField = AppStore.class.getDeclaredField("state");
        stateField.setAccessible(true);
        JSONObject committed = (JSONObject) stateField.get(store);
        JSONObject guardedRoot = new JSONObject(committed.toString()) {
            @Override public String toString() { throw new AssertionError("getter serialized the entire state"); }
        };
        stateField.set(store, guardedRoot);

        store.settings().put("owner", "mutated");
        store.events().put(new JSONObject().put("id", "outside"));
        store.words().getJSONObject(firstWordId).put("word", "mutated");
        store.pack().put("packageId", "mutated");
        store.session().put("position", 99);

        stateField.set(store, committed);
        JSONObject after = store.snapshot();
        assertEquals(before.getJSONObject("settings").optString("owner"), after.getJSONObject("settings").optString("owner"));
        assertEquals(before.getJSONArray("events").length(), after.getJSONArray("events").length());
        assertEquals(before.getJSONObject("words").getJSONObject(firstWordId).getString("word"),
                after.getJSONObject("words").getJSONObject(firstWordId).getString("word"));
        assertEquals(before.getJSONObject("pack").getString("packageId"), after.getJSONObject("pack").getString("packageId"));
        assertEquals(0, after.getJSONObject("session").getInt("position"));
    }

    @Test public void revealIsIdempotentAndAnswerIsGuardedSelfReport() throws Exception {
        TrackingAtomicFile files = installTrackingFile();
        Field stateField = AppStore.class.getDeclaredField("state");
        stateField.setAccessible(true);
        JSONObject previousCommittedRoot = (JSONObject) stateField.get(store);
        String previousRootJson = previousCommittedRoot.toString();
        JSONObject revealed = store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        assertTrue(revealed.getBoolean("revealed"));
        assertEquals("successful copy-on-write reveal must not mutate the prior committed root",
                previousRootJson, previousCommittedRoot.toString());
        int writes = files.writeStarts;

        JSONObject repeated = store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        assertTrue(repeated.getBoolean("revealed"));
        assertEquals("already-revealed gesture must not persist again", writes, files.writeStarts);
        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 1, "remember"));
        expectIllegalArgument(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "correct"));
        assertEquals(writes, files.writeStarts);

        JSONObject next = store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "remember");
        assertEquals(1, next.getInt("position"));
        assertFalse(next.getBoolean("revealed"));
        assertTrue(store.hasGestureUndo());
        JSONArray events = store.events();
        assertEquals(1, events.length());
        JSONObject answer = events.getJSONObject(0);
        assertEquals("answer", answer.getString("type"));
        assertEquals("remember", answer.getString("result"));
        assertEquals("self-report", answer.getString("evidence"));
        assertEquals("forward", answer.getString("mode"));
        assertEquals("answer copy-on-write must leave the old event/session branch intact",
                previousRootJson, previousCommittedRoot.toString());
    }

    @Test public void gestureUndoSurvivesRestartAndRestoresPriorCardAsAssisted() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject advanced = store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        assertEquals(1, advanced.getInt("position"));
        assertTrue(store.hasGestureUndo());

        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        store = AppStore.get(context);
        assertTrue("gesture undo checkpoint must survive process recreation", store.hasGestureUndo());

        JSONObject restored = store.undoLastGestureAnswer();
        assertEquals(0, restored.getInt("position"));
        assertEquals(firstWordId, restored.getJSONArray("queue").getJSONObject(0).getString("wordId"));
        assertTrue(restored.getBoolean("revealed"));
        assertTrue("a card whose answer was seen cannot be retried as independent", restored.getBoolean("assisted"));
        assertFalse(store.hasGestureUndo());
        assertEquals(2, store.events().length());
        JSONObject undo = store.events().getJSONObject(1);
        assertEquals("undo", undo.getString("type"));
        assertEquals(store.events().getJSONObject(0).getString("id"), undo.getString("targetEventId"));
        StudyEngine.Card projection = StudyEngine.project(store.events(), System.currentTimeMillis()).get(firstWordId);
        assertTrue("undone gesture evidence must not affect projected mastery", projection == null || projection.selfRemember == 0);
    }

    @Test public void atomicWriteFailureLeavesSessionAndDiskUnchanged() throws Exception {
        TrackingAtomicFile files = installTrackingFile();
        String before = new String(java.nio.file.Files.readAllBytes(files.delegate.getBaseFile().toPath()), StandardCharsets.UTF_8);
        JSONObject beforeState = store.snapshot();
        JSONObject beforeSession = store.session();
        files.failNextFinish = true;

        try {
            store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
            fail("injected AtomicFile finish failure should surface");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("synthetic AtomicFile"));
        }

        assertEquals(beforeSession.toString(), store.session().toString());
        assertEquals("failed write must not mutate any shared committed branch", beforeState.toString(), store.snapshot().toString());
        assertEquals(before, new String(java.nio.file.Files.readAllBytes(files.delegate.getBaseFile().toPath()), StandardCharsets.UTF_8));
        assertEquals(0, store.events().length());
    }

    @Test public void answerRejectsInputEnabledGestureWithoutAppendingEvent() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject inputEnabled = store.session();
        inputEnabled.put("inputEnabled", true);
        store.saveSession(inputEnabled);
        long seq = store.snapshot().getLong("seq");

        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "remember"));

        assertEquals(seq, store.snapshot().getLong("seq"));
        assertEquals(0, store.events().length());
        assertTrue(store.session().getBoolean("inputEnabled"));
    }

    @Test public void normalAnswerInvalidatesGestureUndoCheckpoint() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject next = store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "remember");
        String nextWord = next.getJSONArray("queue").getJSONObject(1).getString("wordId");
        store.answer(nextWord, session.getString("packageId"), "forward", "correct", "primary", false, false);
        assertFalse("a later ordinary answer must invalidate gesture undo", store.hasGestureUndo());
    }

    @Test public void syncInvalidatesGestureUndoCheckpoint() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        assertTrue(store.hasGestureUndo());
        store.acceptSyncPackages(Collections.emptyList(), store.events(), Collections.emptySet(), "");
        assertFalse("accepted sync must invalidate a stale undo checkpoint", store.hasGestureUndo());
    }

    @Test public void revealRejectsAWordRemovedByLatestSyncedProgress() throws Exception {
        JSONObject remoteAnswer = new JSONObject().put("id", UUID.randomUUID().toString())
                .put("deviceId", "remote-device").put("seq", 1).put("at", System.currentTimeMillis())
                .put("wordId", firstWordId).put("packageId", session.getString("packageId"))
                .put("type", "answer").put("mode", "forward").put("result", "correct")
                .put("attempt", "primary").put("assisted", false).put("isReview", false);
        store.acceptSyncPackages(Collections.emptyList(), new JSONArray().put(remoteAnswer), Collections.emptySet(), "");
        TrackingAtomicFile files = installTrackingFile();
        int writes = files.writeStarts;

        expectIllegalState(() -> store.revealForGesture(session.getString("sessionId"), firstWordId, 0));
        assertEquals("stale gesture rejection must not commit", writes, files.writeStarts);
    }

    @Test public void answerRejectsProgressSyncedBetweenRevealAndAnswer() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject remoteAnswer = remoteAnswer(firstWordId);
        store.acceptSyncPackages(Collections.emptyList(), new JSONArray().put(remoteAnswer), Collections.emptySet(), "");
        long seqAfterSync = store.snapshot().getLong("seq");
        int eventsAfterSync = store.events().length();

        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "remember"));

        assertEquals("rejected stale answer must not advance the local sequence", seqAfterSync, store.snapshot().getLong("seq"));
        assertEquals("rejected stale answer must not append a local event", eventsAfterSync, store.events().length());
        assertEquals("the only event is the newly synced remote answer", "remote-device",
                store.events().getJSONObject(0).getString("deviceId"));
    }

    @Test public void answerRejectsWordWithdrawnBetweenRevealAndAnswer() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject currentPack = store.pack();
        String secondWordId = currentPack.getJSONArray("words").getJSONObject(1).getString("id");
        JSONObject withdrawnRevision = v2RevisionWithWithdrawal(currentPack, secondWordId);
        store.acceptSyncPackages(Collections.singletonList(withdrawnRevision), new JSONArray(), Collections.emptySet(), "");
        long seqAfterSync = store.snapshot().getLong("seq");

        assertFalse("withdrawn definition must disappear from the active word view", store.words().has(firstWordId));
        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard"));

        assertEquals("rejected answer for a withdrawn word must not advance local sequence", seqAfterSync, store.snapshot().getLong("seq"));
        assertEquals(0, store.events().length());
    }

    @Test public void answerRejectsAfterTodayQuotaIsReducedToReviewOnly() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject settings = store.settings();
        settings.put("limitMode", "review");
        store.saveSettings(settings);
        long seq = store.snapshot().getLong("seq");

        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard"));

        assertEquals(seq, store.snapshot().getLong("seq"));
        assertEquals(0, store.events().length());
    }

    @Test public void quotaChangeCannotUndoGestureAnswerWhenItsCardLeavesTheQueue() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        assertTrue(store.hasGestureUndo());
        JSONObject settings = store.settings();
        settings.put("limitMode", "review");
        store.saveSettings(settings);
        long seq = store.snapshot().getLong("seq");
        int events = store.events().length();
        String answerEvent = store.events().getJSONObject(0).toString();

        assertFalse("a quota change that removes the next card must invalidate gesture undo", store.hasGestureUndo());
        expectIllegalState(() -> store.undoLastGestureAnswer());

        assertEquals("quota-stale undo must not append an event", seq, store.snapshot().getLong("seq"));
        assertEquals(events, store.events().length());
        assertEquals("invalidating the checkpoint must preserve the committed grade event", answerEvent,
                store.events().getJSONObject(0).toString());
    }

    @Test public void answerRejectsWhenRevealedSessionHasRolledToPreviousStudyDay() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject yesterday = store.session();
        yesterday.put("date", LocalDate.now(StudyEngine.STUDY_ZONE).minusDays(1).toString());
        store.saveSession(yesterday);
        long seq = store.snapshot().getLong("seq");

        expectIllegalState(() -> store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "remember"));

        assertEquals("day-rollover rejection must not append an event", seq, store.snapshot().getLong("seq"));
        assertEquals(0, store.events().length());
    }

    @Test public void dayRolloverMakesPersistedGestureUndoUnavailableWithoutWriting() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        JSONObject yesterday = store.session();
        yesterday.put("date", LocalDate.now(StudyEngine.STUDY_ZONE).minusDays(1).toString());
        store.saveSession(yesterday);
        long seq = store.snapshot().getLong("seq");
        int events = store.events().length();

        assertFalse("old-day checkpoint must not be offered", store.hasGestureUndo());
        expectIllegalState(() -> store.undoLastGestureAnswer());

        assertEquals("old-day undo rejection must not append an event", seq, store.snapshot().getLong("seq"));
        assertEquals(events, store.events().length());
    }

    @Test public void gestureUndoSurvivesRestartAfterQueueFinished() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject second = store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        String secondWordId = second.getJSONArray("queue").getJSONObject(1).getString("wordId");
        store.revealForGesture(session.getString("sessionId"), secondWordId, 1);
        JSONObject finished = store.answerAndAdvanceGesture(session.getString("sessionId"), secondWordId, 1, "remember");
        assertEquals(finished.getJSONArray("queue").length(), finished.getInt("position"));
        assertTrue(finished.has("completedAt"));
        assertTrue(store.hasGestureUndo());

        AppStore reopened = reopenStore();
        assertTrue("finished-card checkpoint must remain available after process recreation", reopened.hasGestureUndo());
        JSONObject restored = reopened.undoLastGestureAnswer();

        assertEquals(1, restored.getInt("position"));
        assertEquals(secondWordId, restored.getJSONArray("queue").getJSONObject(1).getString("wordId"));
        assertTrue(restored.getBoolean("revealed"));
        assertTrue(restored.getBoolean("assisted"));
        assertFalse(reopened.hasGestureUndo());
        assertEquals("answer, answer, undo", 3, reopened.events().length());
    }

    @Test public void checkpointGuardRejectsPositionOrSessionReplacement() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        JSONObject advanced = store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        assertTrue(store.hasGestureUndo());
        advanced.put("position", 0);
        store.saveSession(advanced);
        assertFalse("moving away from the checkpoint's next card invalidates it", store.hasGestureUndo());
    }

    @Test public void checkpointSurvivesReopenAndSyncThenInvalidatesIt() throws Exception {
        store.revealForGesture(session.getString("sessionId"), firstWordId, 0);
        store.answerAndAdvanceGesture(session.getString("sessionId"), firstWordId, 0, "hard");
        assertTrue(store.hasGestureUndo());
        AppStore refreshed = reopenStore();
        assertTrue(refreshed.hasGestureUndo());
        refreshed.acceptSyncPackages(Collections.emptyList(), refreshed.events(), Collections.emptySet(), "");
        assertFalse(refreshed.hasGestureUndo());
    }

    private AppStore reopenStore() throws Exception {
        Field singleton = AppStore.class.getDeclaredField("instance");
        singleton.setAccessible(true); singleton.set(null, null);
        return AppStore.get(context);
    }

    private TrackingAtomicFile installTrackingFile() throws Exception {
        Field fileField = AppStore.class.getDeclaredField("file");
        fileField.setAccessible(true);
        AtomicFile original = (AtomicFile) fileField.get(store);
        TrackingAtomicFile tracking = new TrackingAtomicFile(original);
        fileField.set(store, tracking);
        return tracking;
    }

    private static void expectIllegalState(ThrowingAction action) throws Exception {
        try { action.run(); fail("expected stale-gesture rejection"); }
        catch (IllegalStateException expected) { }
    }

    private static void expectIllegalArgument(ThrowingAction action) throws Exception {
        try { action.run(); fail("expected invalid-result rejection"); }
        catch (IllegalArgumentException expected) { }
    }

    private static JSONObject packageFor(String date) throws Exception {
        JSONArray words = new JSONArray(), newIds = new JSONArray();
        for (int i = 1; i <= 2; i++) {
            String id = "dyn:gesture-" + i;
            words.put(new JSONObject().put("id", id).put("word", "synthetic" + i).put("rank", i)
                    .put("phonetic", "").put("meaning", "合成释义" + i).put("generatedBy", "AI")
                    .put("examples", new JSONArray().put(new JSONObject().put("en", "A synthetic example.").put("zh", "合成例句。")))
                    .put("senses", new JSONArray().put("合成释义"))
                    .put("questions", new JSONArray()
                            .put(question(id + "-forward", "forward"))
                            .put(question(id + "-reverse", "reverse"))));
            newIds.put(id);
        }
        return new JSONObject().put("schemaVersion", 1).put("packageId", "gesture-" + date).put("date", date)
                .put("timezone", "Asia/Shanghai").put("contentMode", "ai-authored")
                .put("catalogSha256", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
                .put("newWordLimit", 10).put("words", words).put("newWordIds", newIds).put("reviewWordIds", new JSONArray());
    }

    private static JSONObject remoteAnswer(String wordId) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString())
                .put("deviceId", "remote-device").put("seq", 1).put("at", System.currentTimeMillis())
                .put("wordId", wordId).put("packageId", "gesture-" + LocalDate.now(StudyEngine.STUDY_ZONE))
                .put("type", "answer").put("mode", "forward").put("result", "correct")
                .put("attempt", "primary").put("assisted", false).put("isReview", false);
    }

    private static JSONObject v2RevisionWithWithdrawal(JSONObject previous, String remainingWordId) throws Exception {
        JSONObject remaining = null;
        JSONArray oldWords = previous.getJSONArray("words");
        for (int i = 0; i < oldWords.length(); i++) {
            JSONObject candidate = oldWords.getJSONObject(i);
            if (remainingWordId.equals(candidate.getString("id"))) remaining = new JSONObject(candidate.toString());
        }
        if (remaining == null) throw new IllegalArgumentException("remaining synthetic word not found");
        JSONArray questions = remaining.getJSONArray("questions");
        for (int i = 0; i < questions.length(); i++) questions.getJSONObject(i).put("type", "choice");
        return new JSONObject().put("schemaVersion", 2).put("packageId", previous.getString("packageId"))
                .put("date", previous.getString("date")).put("timezone", previous.getString("timezone"))
                .put("contentMode", previous.getString("contentMode")).put("catalogSha256", previous.getString("catalogSha256"))
                .put("revision", 1).put("kind", "full")
                .put("requiredCapabilities", new JSONArray().put("plain-text").put("choice-v1").put("word-withdrawal-v1"))
                .put("newWordLimit", previous.getInt("newWordLimit")).put("words", new JSONArray().put(remaining))
                .put("newWordIds", new JSONArray().put(remainingWordId)).put("reviewWordIds", new JSONArray())
                .put("withdrawnWordIds", new JSONArray().put("dyn:gesture-1"));
    }

    private static JSONObject question(String id, String mode) throws Exception {
        return new JSONObject().put("id", id).put("mode", mode).put("prompt", "Choose the answer.")
                .put("options", new JSONArray().put("正确").put("错误")).put("correctIndex", 0)
                .put("explanation", "合成解释。");
    }

    private interface ThrowingAction { void run() throws Exception; }

    private static final class TrackingAtomicFile extends AtomicFile {
        final AtomicFile delegate;
        int writeStarts;
        boolean failNextFinish;

        TrackingAtomicFile(AtomicFile delegate) {
            super(delegate.getBaseFile()); this.delegate = delegate;
        }

        @Override public FileOutputStream startWrite() throws java.io.IOException {
            writeStarts++;
            return delegate.startWrite();
        }

        @Override public void finishWrite(FileOutputStream stream) {
            if (failNextFinish) {
                failNextFinish = false;
                throw new IllegalStateException("synthetic AtomicFile finish failure");
            }
            delegate.finishWrite(stream);
        }

        @Override public void failWrite(FileOutputStream stream) {
            delegate.failWrite(stream);
        }
    }
}
