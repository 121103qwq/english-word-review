package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class SessionPolicyTest {
    private JSONObject session() throws Exception {
        JSONObject pack = TestFixtures.packageWith(List.of("csv:0001", "csv:0002", "csv:0003"), List.of());
        JSONObject words = new JSONObject();
        for (int i = 0; i < pack.getJSONArray("words").length(); i++) { JSONObject w = pack.getJSONArray("words").getJSONObject(i); words.put(w.getString("id"), w); }
        JSONArray queue = new JSONArray();
        for (String id : List.of("csv:0001", "csv:0002")) queue.put(new JSONObject().put("wordId", id).put("review", false).put("attempt", "primary"));
        return new JSONObject().put("date", "2026-01-01").put("packageId", pack.getString("packageId")).put("pack", pack).put("words", words)
                .put("queue", queue).put("position", 0).put("questionIndex", 1).put("assisted", false);
    }
    @Test public void remoteAnswersRemoveStaleQuestionsAndUseRemainingPersonalQuota() throws Exception {
        JSONObject s = session(); long now = TestFixtures.at("2026-01-01", 12, 0);
        JSONArray events = new JSONArray().put(TestFixtures.answer("00000000-0000-0000-0000-000000000001", 1, now - 1000, "csv:0001", "correct", "forward", "primary", false));
        JSONObject next = SessionPolicy.reconcile(s, events, s.getJSONObject("words"), now, 2);
        assertEquals(1, next.getJSONArray("queue").length());
        assertEquals("csv:0002", next.getJSONArray("queue").getJSONObject(0).getString("wordId"));
        assertFalse(next.has("questionIndex")); assertEquals(2, s.getJSONArray("queue").length());
    }
    @Test public void knownSkipReplenishesFromFrozenPackageAndRetainsFeedback() throws Exception {
        JSONObject s = session(); long now = TestFixtures.at("2026-01-01", 12, 0);
        JSONObject skip = TestFixtures.skip("00000000-0000-0000-0000-000000000001", 1, now - 1000, "csv:0001");
        s.put("feedbackEvent", skip);
        JSONObject available = new JSONObject(s.getJSONObject("words").toString()).put("csv:0099", TestFixtures.word("csv:0099", 99));
        JSONObject next = SessionPolicy.reconcile(s, new JSONArray().put(skip), available, now, 2);
        assertEquals(3, next.getJSONArray("queue").length());
        assertEquals("csv:0003", next.getJSONArray("queue").getJSONObject(2).getString("wordId"));
        assertEquals("daily-2026-01-01", next.getString("packageId")); assertTrue(next.has("feedbackEvent"));
    }
    @Test public void reviewOnlyWithdrawalsAndMidnightDoNotLeaveAnswerableNewQuestions() throws Exception {
        JSONObject s = session(); long now = TestFixtures.at("2026-01-01", 12, 0);
        assertEquals(0, SessionPolicy.reconcile(s, new JSONArray(), s.getJSONObject("words"), now, 0).getJSONArray("queue").length());
        assertEquals(0, SessionPolicy.reconcile(s, new JSONArray(), new JSONObject(), now, -1).getJSONArray("queue").length());
        assertNull(SessionPolicy.reconcile(s, new JSONArray(), s.getJSONObject("words"), TestFixtures.at("2026-01-02", 0, 0), -1));
    }
}
