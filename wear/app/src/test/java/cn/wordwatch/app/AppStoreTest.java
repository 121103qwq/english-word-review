package cn.wordwatch.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppStoreTest {
    @Test public void upgradeChangesOldDefaultWithoutChangingLearningRecords() throws Exception {
        JSONObject state = new JSONObject().put("settings", new JSONObject().put("newLimit", 15)
                .put("owner", "example").put("repo", "private-study").put("branch", "main"))
                .put("events", new JSONArray().put(new JSONObject().put("id", "preserved-event")))
                .put("session", new JSONObject().put("position", 7)).put("lastSyncedSeq", 12);
        String events = state.getJSONArray("events").toString();
        assertTrue(AppStore.upgradeDailyLimit(state));
        assertEquals(30, state.getJSONObject("settings").getInt("newLimit"));
        assertEquals("example", state.getJSONObject("settings").getString("owner"));
        assertEquals(events, state.getJSONArray("events").toString());
        assertEquals(7, state.getJSONObject("session").getInt("position"));
        assertEquals(12, state.getInt("lastSyncedSeq"));
        state.getJSONObject("settings").put("newLimit", 15);
        assertFalse(AppStore.upgradeDailyLimit(state));
        assertEquals(15, state.getJSONObject("settings").getInt("newLimit"));
    }

    @Test public void upgradePreservesOtherExplicitLimits() throws Exception {
        for (int limit : new int[]{0, 10, 20, 30, 50}) {
            JSONObject state = new JSONObject().put("settings", new JSONObject().put("newLimit", limit));
            AppStore.upgradeDailyLimit(state);
            assertEquals(limit, state.getJSONObject("settings").getInt("newLimit"));
        }
    }
}
