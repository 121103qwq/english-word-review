package cn.wordwatch.app;

import cn.wordwatch.app.core.LearningSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.*;

public class StorePolicyTest {
    @Test public void partialUploadCannotAcknowledgeEarlierMissingEvents() throws Exception {
        JSONArray events = new JSONArray();
        for (int i = 1; i <= 4; i++) events.put(new JSONObject().put("id", "e" + i).put("deviceId", "one").put("seq", i));
        JSONObject state = new JSONObject().put("deviceId", "one").put("events", events).put("lastSyncedSeq", 0);
        AppStore.acknowledge(state, Set.of("e3", "e4"));
        assertEquals(0, state.getLong("lastSyncedSeq")); assertEquals(2, AppStore.pendingCount(state));
        AppStore.acknowledge(state, Set.of("e1"));
        assertEquals(1, state.getLong("lastSyncedSeq")); assertEquals(1, AppStore.pendingCount(state));
        AppStore.acknowledge(state, Set.of("e2"));
        assertEquals(4, state.getLong("lastSyncedSeq")); assertEquals(0, AppStore.pendingCount(state));
    }
    @Test public void datedPacksActivateOnTheirOwnDay() throws Exception {
        JSONObject first = new JSONObject().put("date", "2030-01-01").put("words", new JSONArray().put(new JSONObject().put("id", "csv:0001")));
        JSONObject next = new JSONObject().put("date", "2030-01-02").put("words", new JSONArray().put(new JSONObject().put("id", "csv:0002")))
                .put("withdrawnWordIds", new JSONArray().put("csv:0001"));
        JSONObject state = new JSONObject().put("words", new JSONObject()).put("packages", new JSONObject().put("2030-01-01", first).put("2030-01-02", next));
        assertTrue(AppStore.activatePackage(state, "2030-01-01"));
        assertEquals("2030-01-01", state.getJSONObject("pack").getString("date"));
        assertFalse(state.getJSONObject("words").has("csv:0002"));
        assertTrue(AppStore.activatePackage(state, "2030-01-02"));
        assertTrue(state.getJSONObject("withdrawn").getBoolean("csv:0001"));
        assertFalse(AppStore.activatePackage(state, "2030-01-02"));
    }
    @Test public void defaultFollowsPackageWhilePersonalAndReviewModesRemainAvailable() throws Exception {
        JSONObject settings = new JSONObject().put("newLimit", 30);
        assertEquals(-1, LearningSettings.newLimit(settings));
        settings.put("limitMode", "custom").put("newLimit", 75);
        assertEquals(75, LearningSettings.newLimit(settings));
        settings.put("limitMode", "review"); assertEquals(0, LearningSettings.newLimit(settings));
        settings.put("limitMode", "package"); assertEquals(-1, LearningSettings.newLimit(settings));
    }
}
