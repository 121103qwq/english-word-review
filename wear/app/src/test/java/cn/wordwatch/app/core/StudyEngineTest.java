package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StudyEngineTest {
    @Test
    public void packageLimitsAllowUnrestrictedLegacyValuesButRejectInvalidBounds() throws Exception {
        JSONObject pack = TestFixtures.packageWith(Collections.singletonList("csv:0001"), Collections.emptyList());
        for (int limit : new int[]{0, 15, 20, 30, 10000}) {
            pack.put("newWordLimit", limit);
            StudyEngine.validatePackage(pack);
        }
        for (int limit : new int[]{-1, 10001}) {
            pack.put("newWordLimit", limit);
            expectIllegalArgument(() -> StudyEngine.validatePackage(pack));
        }
    }

    @Test
    public void queueUsesPackageLimitOrOptionalUserLimitWithoutHardCodedThirty() throws Exception {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 45; i++) ids.add(String.format(java.util.Locale.ROOT, "csv:%04d", i));
        JSONObject pack = TestFixtures.packageWith(ids, Collections.emptyList());
        long now = TestFixtures.at("2026-01-01", 12, 0);
        assertEquals(15, StudyEngine.queue(pack, new JSONObject(), new JSONArray(), now, 30).size());
        pack.put("newWordLimit", 100);
        assertEquals(45, StudyEngine.queue(pack, new JSONObject(), new JSONArray(), now, -1).size());
        assertEquals(20, StudyEngine.queue(pack, new JSONObject(), new JSONArray(), now, 20).size());
        assertEquals(0, StudyEngine.queue(pack, new JSONObject(), new JSONArray(), now, 0).size());
    }

    @Test
    public void futureDatedPackageDoesNotStartAnyStudyTasks() throws Exception {
        JSONObject future = TestFixtures.contractVectors().getJSONObject("full");
        assertTrue(StudyEngine.queue(future, new JSONObject(), new JSONArray(),
                TestFixtures.at("2026-01-01", 12, 0), -1).isEmpty());
    }

    @Test
    public void sharedV2FullAndDeltaVectorsValidateApplyAndRemainIdempotent() throws Exception {
        JSONObject vectors = TestFixtures.contractVectors();
        JSONObject full = vectors.getJSONObject("full");
        JSONObject delta = vectors.getJSONObject("delta");
        StudyEngine.validatePackage(full);
        StudyEngine.validatePackage(delta);
        assertEquals(1, StudyEngine.revision(full));
        assertEquals(2, StudyEngine.revision(delta));

        JSONObject applied = StudyEngine.applyPackage(full, delta);

        assertEquals(2, StudyEngine.revision(applied));
        assertEquals("full", applied.getString("kind"));
        assertEquals(1, applied.getJSONArray("words").length());
        assertEquals("csv:0001", applied.getJSONArray("words").getJSONObject(0).getString("id"));
        assertEquals("csv:0002", applied.getJSONArray("withdrawnWordIds").getString(0));
        assertTrue(applied.getJSONObject("x-package-optional").getBoolean("retained"));
        assertTrue(applied.getJSONObject("x-delta-optional").getBoolean("retained"));
        assertTrue(applied.getJSONArray("requiredCapabilities").toString().contains("word-withdrawal-v1"));
        assertEquals(2, StudyEngine.revision(StudyEngine.applyPackage(applied, delta)));
    }

    @Test
    public void v2RejectsUnsupportedRequiredCapabilityAndConflictingWordIdentity() throws Exception {
        JSONObject vectors = TestFixtures.contractVectors();
        JSONObject unsupported = vectors.getJSONObject("full");
        unsupported.getJSONArray("requiredCapabilities").put("future-feature-v9");
        expectIllegalArgument(() -> StudyEngine.validatePackage(unsupported));

        vectors = TestFixtures.contractVectors();
        JSONObject full = vectors.getJSONObject("full");
        JSONObject delta = vectors.getJSONObject("delta");
        delta.getJSONArray("words").getJSONObject(0).put("word", "gamma");
        JSONObject finalDelta = delta;
        expectIllegalArgument(() -> StudyEngine.applyPackage(full, finalDelta));
    }

    @Test
    public void matchesAnswerUsesTrimUnicodeCaseAndOnlyExplicitPunctuationPolicy() throws Exception {
        JSONObject question = new JSONObject()
                .put("type", "input").put("acceptedAnswers", new JSONArray().put("Café"))
                .put("caseSensitive", false);
        assertTrue(StudyEngine.matchesAnswer(question, "  Cafe\u0301  "));
        assertFalse(StudyEngine.matchesAnswer(question, "cafe"));
        question.put("acceptedAnswers", new JSONArray().put("can't"))
                .put("ignorePunctuation", true);
        assertTrue(StudyEngine.matchesAnswer(question, "cant"));
        JSONObject recall = new JSONObject().put("type", "recall").put("answer", "Alpha");
        assertTrue(StudyEngine.matchesAnswer(recall, "Alpha"));
        assertFalse(StudyEngine.matchesAnswer(recall, "alpha"));
    }

    @Test
    public void mergeEventsDeduplicatesIdenticalIdsAndSortsDeterministically() throws Exception {
        long firstAt = TestFixtures.at("2026-01-01", 8, 0);
        long secondAt = TestFixtures.at("2026-01-01", 9, 0);
        JSONObject first = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000001", 2, firstAt,
                "csv:0001", "correct", "forward", "primary", false);
        JSONObject second = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000002", 1, secondAt,
                "csv:0002", "wrong", "forward", "primary", false);
        JSONArray left = new JSONArray().put(second).put(first);
        JSONArray right = new JSONArray().put(new JSONObject(first.toString()));

        JSONArray merged = StudyEngine.mergeEvents(left, right);

        assertEquals(2, merged.length());
        assertEquals(first.optString("id"), merged.getJSONObject(0).optString("id"));
        assertEquals(second.optString("id"), merged.getJSONObject(1).optString("id"));
        // Inputs remain untouched and the output is a detached JSON union.
        merged.getJSONObject(0).put("result", "wrong");
        assertEquals("correct", first.optString("result"));
    }

    @Test
    public void mergeEventsRejectsSameIdWithDifferentContents() throws Exception {
        String id = "00000000-0000-0000-0000-000000000010";
        JSONObject first = TestFixtures.answer(
                id, 1, TestFixtures.at("2026-01-01", 8, 0),
                "csv:0001", "correct", "forward", "primary", false);
        JSONObject conflicting = TestFixtures.answer(
                id, 1, TestFixtures.at("2026-01-01", 8, 0),
                "csv:0001", "wrong", "forward", "primary", false);

        expectIllegalArgument(() -> StudyEngine.mergeEvents(
                new JSONArray().put(first), new JSONArray().put(conflicting)));
    }

    @Test
    public void projectionUsesShanghaiCalendarDaysAndOnlyAdvancesWhenDue() throws Exception {
        long firstAt = TestFixtures.at("2026-01-01", 10, 0);
        long earlyAt = TestFixtures.at("2026-01-01", 11, 0);
        long dueAt = TestFixtures.at("2026-01-02", 0, 10);
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000011", 1, firstAt,
                        "csv:0001", "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000012", 2, earlyAt,
                        "csv:0001", "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000013", 3, dueAt,
                        "csv:0001", "correct", "reverse", "primary", true));

        StudyEngine.Card card = StudyEngine.project(
                events, TestFixtures.at("2026-01-02", 0, 11)).get("csv:0001");

        assertNotNull(card);
        assertEquals(1, card.stage);
        assertEquals(TestFixtures.at("2026-01-05", 0, 0), card.dueAt);
        assertEquals(3, card.objectiveRight);
        assertEquals(dueAt, card.lastAt);
    }

    @Test
    public void wrongResetsScheduleRetryDoesNotChangeItAndUndoReprojects() throws Exception {
        long firstAt = TestFixtures.at("2026-01-01", 10, 0);
        long wrongAt = TestFixtures.at("2026-01-02", 1, 0);
        long retryAt = TestFixtures.at("2026-01-02", 2, 0);
        long undoAt = TestFixtures.at("2026-01-02", 3, 0);
        String correctId = "00000000-0000-0000-0000-000000000021";
        String wrongId = "00000000-0000-0000-0000-000000000022";
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        correctId, 1, firstAt, "csv:0001", "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        wrongId, 2, wrongAt, "csv:0001", "wrong", "forward", "primary", true))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000023", 3, retryAt,
                        "csv:0001", "correct", "forward", "retry", true));

        StudyEngine.Card beforeUndo = StudyEngine.project(events, undoAt).get("csv:0001");
        assertEquals(0, beforeUndo.stage);
        assertEquals(TestFixtures.at("2026-01-03", 0, 0), beforeUndo.dueAt);
        assertEquals(1, beforeUndo.objectiveWrong);

        events.put(TestFixtures.undo(
                "00000000-0000-0000-0000-000000000024", 4, undoAt, wrongId));
        StudyEngine.Card afterUndo = StudyEngine.project(events, undoAt).get("csv:0001");

        assertEquals(0, afterUndo.stage);
        assertEquals(TestFixtures.at("2026-01-02", 0, 0), afterUndo.dueAt);
        assertEquals(0, afterUndo.objectiveWrong);
        assertEquals(2, afterUndo.objectiveRight);
    }

    @Test
    public void skipMarksKnownWithoutMasteryOrReviewSchedulingAndQueueOmitsIt() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                Arrays.asList("csv:0001", "csv:0002"), Collections.emptyList());
        long now = TestFixtures.at("2026-01-01", 12, 0);
        JSONArray events = new JSONArray().put(TestFixtures.skip(
                "00000000-0000-0000-0000-000000000031", 1,
                TestFixtures.at("2026-01-01", 8, 0), "csv:0001"));

        StudyEngine.Card skipped = StudyEngine.project(events, now).get("csv:0001");
        List<StudyEngine.Task> queue = StudyEngine.queue(
                pack, new JSONObject(), events, now, 15);
        int[] counts = StudyEngine.todayCounts(events, now);

        assertTrue(skipped.known);
        assertEquals(0, skipped.dueAt);
        assertEquals(0, skipped.objectiveRight);
        assertEquals(0, skipped.objectiveWrong);
        assertEquals(1, queue.size());
        assertEquals("csv:0002", queue.get(0).wordId);
        assertEquals(0, counts[0]);
        assertEquals(0, counts[1]);
        assertEquals(1, counts[2]);
    }

    @Test
    public void selfAssessmentRecallNeverCountsAsObjectiveSpellingSuccess() throws Exception {
        long now = TestFixtures.at("2026-01-01", 12, 0);
        JSONArray events = new JSONArray().put(TestFixtures.answer(
                "00000000-0000-0000-0000-000000000035", 1,
                TestFixtures.at("2026-01-01", 8, 0), "csv:0001",
                "remember", "recall", "primary", false));

        StudyEngine.Card card = StudyEngine.project(events, now).get("csv:0001");

        assertEquals(0, card.objectiveRight);
        assertEquals(0, card.objectiveWrong);
        assertEquals(1, card.selfRemember);
        assertEquals(TestFixtures.at("2026-01-02", 0, 0), card.dueAt);
    }

    @Test
    public void laterPrimaryAnswerRestoresAWordPreviouslySkippedAsKnown() throws Exception {
        JSONArray events = new JSONArray()
                .put(TestFixtures.skip(
                        "00000000-0000-0000-0000-000000000036", 1,
                        TestFixtures.at("2026-01-01", 9, 0), "csv:0001"))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000037", 2,
                        TestFixtures.at("2026-01-02", 10, 0), "csv:0001",
                        "correct", "forward", "primary", false));
        long now = TestFixtures.at("2026-01-03", 8, 0);
        JSONObject pack = TestFixtures.packageWith(
                Collections.emptyList(), Collections.singletonList("csv:0001"));

        StudyEngine.Card card = StudyEngine.project(events, now).get("csv:0001");
        List<StudyEngine.Task> queue = StudyEngine.queue(pack, new JSONObject(), events, now, 15);

        assertFalse(card.known);
        assertEquals(0, card.stage);
        assertEquals(TestFixtures.at("2026-01-03", 0, 0), card.dueAt);
        assertEquals(1, card.objectiveRight);
        assertEquals(1, queue.size());
        assertEquals("csv:0001", queue.get(0).wordId);
        assertTrue(queue.get(0).review);
    }

    @Test
    public void reviewIdsDoNotForceCardsBeforeTheirDueTime() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                Collections.emptyList(), Collections.singletonList("csv:0001"));
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000038", 1,
                        TestFixtures.at("2026-01-01", 10, 0), "csv:0001",
                        "correct", "forward", "primary", true))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000060", 2,
                        TestFixtures.at("2026-01-02", 10, 0), "csv:0001",
                        "correct", "reverse", "primary", true));

        List<StudyEngine.Task> queue = StudyEngine.queue(
                pack, new JSONObject(), events, TestFixtures.at("2026-01-03", 12, 0), 15);

        assertTrue(queue.isEmpty());
    }

    @Test
    public void assistedCorrectIsSeparateAndDoesNotAdvanceTheReviewStage() throws Exception {
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000039", 1,
                        TestFixtures.at("2026-01-01", 10, 0), "csv:0001",
                        "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000040", 2,
                        TestFixtures.at("2026-01-02", 0, 10), "csv:0001",
                        "correct", "reverse", "primary", true).put("assisted", true));

        StudyEngine.Card card = StudyEngine.project(
                events, TestFixtures.at("2026-01-02", 0, 20)).get("csv:0001");
        int[] counts = StudyEngine.todayCounts(events, TestFixtures.at("2026-01-02", 0, 20));

        assertEquals(0, card.stage);
        assertEquals(TestFixtures.at("2026-01-02", 0, 0), card.dueAt);
        assertEquals(1, card.objectiveRight);
        assertEquals(1, card.assistedRight);
        assertEquals(0, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(1, counts[3]);
    }

    @Test
    public void eventValidationRejectsMismatchedModesAndRetrySkips() throws Exception {
        long at = TestFixtures.at("2026-01-01", 8, 0);
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(TestFixtures.answer(
                "00000000-0000-0000-0000-000000000056", 1, at, "csv:0001",
                "correct", "recall", "primary", false)), new JSONArray()));
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(TestFixtures.answer(
                "00000000-0000-0000-0000-000000000057", 1, at, "csv:0001",
                "remember", "forward", "primary", false)), new JSONArray()));
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(TestFixtures.answer(
                "00000000-0000-0000-0000-000000000058", 1, at, "csv:0001",
                "known", "skip", "retry", false)), new JSONArray()));
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(TestFixtures.answer(
                "00000000-0000-0000-0000-000000000059", 1, at, "csv:0001",
                "known", "forward", "primary", false)), new JSONArray()));
    }

    @Test
    public void queueReviewsOverdueCachedCardsAndNeverRelearnsPreviouslyStudiedCandidates() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                Arrays.asList("csv:0001", "csv:0002", "csv:0003", "csv:0005", "csv:0006", "csv:0007"),
                Collections.emptyList());
        long jan1 = TestFixtures.at("2026-01-01", 10, 0);
        long jan2Due = TestFixtures.at("2026-01-02", 1, 0);
        long jan3Answer = TestFixtures.at("2026-01-03", 9, 0);
        long now = TestFixtures.at("2026-01-03", 12, 0);
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000041", 1, jan1,
                        "csv:0001", "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000042", 2, jan3Answer,
                        "csv:0002", "correct", "forward", "primary", false))
                .put(TestFixtures.skip(
                        "00000000-0000-0000-0000-000000000043", 3, jan1,
                        "csv:0003"))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000044", 4, jan1,
                        "csv:0004", "correct", "forward", "primary", true))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000045", 5, jan1,
                        "csv:0006", "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000046", 6, jan2Due,
                        "csv:0006", "correct", "reverse", "primary", true));
        JSONObject cache = new JSONObject().put("csv:0004", new JSONObject().put("word", "cached"));

        List<StudyEngine.Task> queue = StudyEngine.queue(pack, cache, events, now, 2);

        assertEquals(3, queue.size());
        assertEquals("csv:0001", queue.get(0).wordId);
        assertTrue(queue.get(0).review);
        assertEquals("csv:0004", queue.get(1).wordId);
        assertTrue(queue.get(1).review);
        assertEquals("csv:0005", queue.get(2).wordId);
        assertFalse(queue.get(2).review);
        assertEquals("primary", queue.get(2).attempt);
    }

    @Test
    public void todayCountsSeparateReviewAndKnownEventsAndIgnoreRetry() throws Exception {
        long today = TestFixtures.at("2026-01-03", 10, 0);
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000051", 1,
                        TestFixtures.at("2026-01-03", 8, 0), "csv:0001",
                        "correct", "forward", "primary", false))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000052", 2,
                        TestFixtures.at("2026-01-03", 8, 30), "csv:0002",
                        "wrong", "reverse", "primary", true))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000053", 3,
                        TestFixtures.at("2026-01-03", 9, 0), "csv:0003",
                        "wrong", "forward", "retry", false))
                .put(TestFixtures.skip(
                        "00000000-0000-0000-0000-000000000054", 4,
                        TestFixtures.at("2026-01-03", 9, 30), "csv:0004"))
                .put(TestFixtures.answer(
                        "00000000-0000-0000-0000-000000000055", 5,
                        TestFixtures.at("2026-01-02", 9, 0), "csv:0005",
                        "correct", "forward", "primary", false));

        int[] counts = StudyEngine.todayCounts(events, today);

        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(1, counts[2]);
    }

    @Test
    public void dailyQuotaDeduplicatesWordsAcrossDevicesSessionsAndAnswerKinds() throws Exception {
        long today = TestFixtures.at("2026-01-03", 10, 0);
        JSONObject newAnswer = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000061", 1,
                TestFixtures.at("2026-01-03", 8, 0), "csv:0001",
                "correct", "custom-mode", "primary", false).put("sessionId", "session-a");
        JSONObject crossDeviceReview = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000062", 1,
                TestFixtures.at("2026-01-03", 8, 30), "csv:0001",
                "correct", "other-mode", "primary", true).put("deviceId", "device-B")
                .put("sessionId", "session-b");
        JSONObject review = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000063", 2,
                TestFixtures.at("2026-01-03", 9, 0), "csv:0002",
                "wrong", "forward", "primary", true);
        JSONArray events = new JSONArray().put(newAnswer).put(crossDeviceReview).put(review);

        int[] counts = StudyEngine.todayCounts(events, today);
        JSONObject pack = TestFixtures.packageWith(
                Arrays.asList("csv:0001", "csv:0002", "csv:0003"), Collections.emptyList());
        List<StudyEngine.Task> queue = StudyEngine.queue(pack, new JSONObject(), events, today, -1);

        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
        assertEquals(1, queue.size());
        assertEquals("csv:0003", queue.get(0).wordId);
    }

    @Test
    public void eventSequenceConflictsRejectButUnknownUndoTargetsAreIgnored() throws Exception {
        long at = TestFixtures.at("2026-01-03", 10, 0);
        JSONObject first = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000064", 7, at,
                "csv:0001", "correct", "forward", "primary", false);
        JSONObject conflict = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000065", 7, at,
                "csv:0002", "wrong", "forward", "primary", false);
        expectIllegalArgument(() -> StudyEngine.mergeEvents(
                new JSONArray().put(first), new JSONArray().put(conflict)));

        JSONObject missingTargetUndo = TestFixtures.undo(
                "00000000-0000-0000-0000-000000000066", 8, at,
                "00000000-0000-0000-0000-000000000099");
        Map<String, StudyEngine.Card> cards = StudyEngine.project(
                new JSONArray().put(first).put(missingTargetUndo), at);
        assertEquals(1, cards.get("csv:0001").objectiveRight);
    }

    @Test
    public void scheduleDaysAreUsedOnlyWhenTheAnswerAdvancesTheCard() throws Exception {
        long firstAt = TestFixtures.at("2026-01-01", 10, 0);
        JSONObject first = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000067", 1, firstAt,
                "csv:0001", "remember", "active-recall", "primary", false)
                .put("evidence", "self-report").put("scheduleDays", 4);
        StudyEngine.Card card = StudyEngine.project(new JSONArray().put(first), firstAt).get("csv:0001");
        assertEquals(TestFixtures.at("2026-01-05", 0, 0), card.dueAt);
    }

    @Test
    public void explicitEventEvidenceOverridesLegacyRecallModeSemantics() throws Exception {
        long at = TestFixtures.at("2026-01-01", 10, 0);
        JSONObject objectiveRecallNamedMode = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000072", 1, at,
                "csv:0001", "correct", "recall", "primary", false)
                .put("evidence", "independent-choice");
        StudyEngine.Card objective = StudyEngine.project(new JSONArray().put(objectiveRecallNamedMode), at)
                .get("csv:0001");
        assertEquals(1, objective.objectiveRight);
        assertEquals(0, objective.selfRemember);

        JSONObject legacySelfReport = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000073", 2, at,
                "csv:0002", "remember", "recall", "primary", false);
        JSONArray validLegacy = StudyEngine.mergeEvents(new JSONArray().put(legacySelfReport), new JSONArray());
        assertEquals(1, StudyEngine.project(validLegacy, at).get("csv:0002").selfRemember);
        JSONObject legacyObjective = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000074", 3, at,
                "csv:0002", "correct", "recall", "primary", false);
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(legacyObjective),
                new JSONArray()));
        JSONObject contradictorySelfReport = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000075", 4, at,
                "csv:0002", "remember", "recall", "primary", false)
                .put("evidence", "independent-choice");
        expectIllegalArgument(() -> StudyEngine.mergeEvents(new JSONArray().put(contradictorySelfReport),
                new JSONArray()));
    }

    @Test
    public void customScheduleCanAdvancePastTheDefaultSixIntervals() throws Exception {
        String[] dates = {"2030-01-01", "2030-01-02", "2030-01-04", "2030-01-07",
                "2030-01-11", "2030-01-16", "2030-01-22", "2030-01-29", "2030-02-06"};
        JSONArray events = new JSONArray();
        for (int index = 0; index < dates.length; index++) {
            String eventId = String.format(java.util.Locale.ROOT,
                    "00000000-0000-0000-0000-%012d", 80 + index);
            JSONObject answer = TestFixtures.answer(eventId, 20 + index,
                    TestFixtures.at(dates[index], 10, 0), "csv:0001", "remember",
                    "custom-mode", "primary", false)
                    .put("evidence", "self-report").put("scheduleDays", index + 1);
            events.put(answer);
        }

        StudyEngine.Card card = StudyEngine.project(events,
                TestFixtures.at("2030-02-06", 12, 0)).get("csv:0001");

        assertEquals(8, card.stage);
        assertEquals(TestFixtures.at("2030-02-15", 0, 0), card.dueAt);
    }

    @Test
    public void assistedSelfReportDoesNotAdvanceExistingCardButSchedulesNewCard() throws Exception {
        long jan1 = TestFixtures.at("2026-01-01", 10, 0);
        long jan2 = TestFixtures.at("2026-01-02", 10, 0);
        long jan6 = TestFixtures.at("2026-01-06", 10, 0);
        JSONObject assistedExisting = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000070", 3, jan6,
                "csv:0001", "remember", "input", "primary", false)
                .put("assisted", true).put("evidence", "self-report").put("scheduleDays", 4);
        JSONObject assistedNew = TestFixtures.answer(
                "00000000-0000-0000-0000-000000000071", 4, jan6,
                "csv:0002", "remember", "input", "primary", false)
                .put("assisted", true).put("evidence", "self-report").put("scheduleDays", 4);
        JSONArray events = new JSONArray()
                .put(TestFixtures.answer("00000000-0000-0000-0000-000000000068", 1, jan1,
                        "csv:0001", "correct", "forward", "primary", false))
                .put(TestFixtures.answer("00000000-0000-0000-0000-000000000069", 2, jan2,
                        "csv:0001", "correct", "forward", "primary", false))
                .put(assistedExisting)
                .put(assistedNew);

        Map<String, StudyEngine.Card> cards = StudyEngine.project(events, jan6);

        StudyEngine.Card existing = cards.get("csv:0001");
        assertEquals(1, existing.stage);
        assertEquals(TestFixtures.at("2026-01-05", 0, 0), existing.dueAt);
        assertEquals(1, existing.selfRemember);
        assertEquals(2, existing.objectiveRight);
        assertTrue(assistedExisting.getBoolean("assisted"));
        assertEquals("self-report", assistedExisting.getString("evidence"));
        StudyEngine.Card newCard = cards.get("csv:0002");
        assertEquals(0, newCard.stage);
        assertEquals(TestFixtures.at("2026-01-10", 0, 0), newCard.dueAt);
        assertEquals(1, newCard.selfRemember);
        assertEquals(0, newCard.objectiveRight);
    }

    @Test
    public void packageValidationRejectsQuestionsWithoutBothAuthoredModes() throws Exception {
        JSONObject pack = TestFixtures.packageWith(
                Collections.singletonList("csv:0001"), Collections.emptyList());
        JSONObject word = pack.getJSONArray("words").getJSONObject(0);
        JSONArray questions = word.getJSONArray("questions");
        questions.getJSONObject(1).put("mode", "rare");

        expectIllegalArgument(() -> StudyEngine.validatePackage(pack));
    }

    private static void expectIllegalArgument(ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected failure.
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
