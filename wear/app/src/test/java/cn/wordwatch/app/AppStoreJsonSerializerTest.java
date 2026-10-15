package cn.wordwatch.app;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

public final class AppStoreJsonSerializerTest {
    @Test public void reusesCommittedContentAndRefreshesReplacedBranches() throws Exception {
        AppStoreJsonSerializer serializer = new AppStoreJsonSerializer();
        JSONObject root = root("first");
        AppStoreJsonSerializer.Prepared initial = serializer.prepare(root);
        assertEquals(0, initial.cacheHits);
        serializer.committed(initial);

        AppStoreJsonSerializer.Prepared unchanged = serializer.prepare(root);
        assertEquals(3, unchanged.cacheHits);
        assertContentEquals(root, unchanged);

        JSONObject updated = shallowRoot(root);
        updated.put("words", new JSONObject().put("w1", new JSONObject().put("word", "updated")));
        assertNotSame(root.getJSONObject("words"), updated.getJSONObject("words"));
        AppStoreJsonSerializer.Prepared changed = serializer.prepare(updated);
        assertEquals(2, changed.cacheHits);
        assertEquals("updated", new JSONObject(new String(changed.bytes, StandardCharsets.UTF_8))
                .getJSONObject("words").getJSONObject("w1").getString("word"));
        serializer.committed(changed);
        assertEquals(3, serializer.prepare(updated).cacheHits);
    }

    @Test public void uncommittedSerializationDoesNotPoisonRetryCache() throws Exception {
        AppStoreJsonSerializer serializer = new AppStoreJsonSerializer();
        JSONObject committedRoot = root("committed");
        serializer.committed(serializer.prepare(committedRoot));

        JSONObject retryRoot = shallowRoot(committedRoot);
        retryRoot.put("pack", new JSONObject().put("packageId", "retry"));
        AppStoreJsonSerializer.Prepared failedAttempt = serializer.prepare(retryRoot);
        assertEquals(2, failedAttempt.cacheHits);
        assertEquals("retry", new JSONObject(new String(failedAttempt.bytes, StandardCharsets.UTF_8))
                .getJSONObject("pack").getString("packageId"));
        // Deliberately do not call committed(): this models an AtomicFile failure.

        assertEquals(3, serializer.prepare(committedRoot).cacheHits);
        AppStoreJsonSerializer.Prepared retry = serializer.prepare(retryRoot);
        assertEquals(2, retry.cacheHits);
        assertEquals("retry", new JSONObject(new String(retry.bytes, StandardCharsets.UTF_8))
                .getJSONObject("pack").getString("packageId"));
        serializer.committed(retry);
        assertTrue(serializer.prepare(retryRoot).cacheHits == 3);
    }

    @Test public void explicitInvalidationCoversInPlaceBootstrapMutation() throws Exception {
        AppStoreJsonSerializer serializer = new AppStoreJsonSerializer();
        JSONObject root = root("before");
        serializer.committed(serializer.prepare(root));
        root.getJSONObject("words").getJSONObject("w1").put("word", "after");

        serializer.invalidate();
        AppStoreJsonSerializer.Prepared prepared = serializer.prepare(root);
        assertEquals(0, prepared.cacheHits);
        assertEquals("after", new JSONObject(new String(prepared.bytes, StandardCharsets.UTF_8))
                .getJSONObject("words").getJSONObject("w1").getString("word"));
    }

    private static JSONObject root(String packageId) throws Exception {
        return new JSONObject().put("seq", 7).put("pack", new JSONObject().put("packageId", packageId))
                .put("packages", new JSONObject().put("2030-01-01", new JSONObject().put("packageId", packageId)))
                .put("words", new JSONObject().put("w1", new JSONObject().put("word", packageId)))
                .put("events", new org.json.JSONArray().put(new JSONObject().put("id", "e1")))
                .put("settings", new JSONObject().put("token", JSONObject.NULL));
    }

    private static JSONObject shallowRoot(JSONObject source) throws Exception {
        JSONObject copy = new JSONObject();
        java.util.Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            copy.put(key, source.get(key));
        }
        return copy;
    }

    private static void assertContentEquals(JSONObject expected, AppStoreJsonSerializer.Prepared actual) throws Exception {
        JSONObject parsed = new JSONObject(new String(actual.bytes, StandardCharsets.UTF_8));
        assertEquals(expected.getJSONObject("pack").toString(), parsed.getJSONObject("pack").toString());
        assertEquals(expected.getJSONObject("packages").toString(), parsed.getJSONObject("packages").toString());
        assertEquals(expected.getJSONObject("words").toString(), parsed.getJSONObject("words").toString());
        assertEquals(expected.getJSONArray("events").toString(), parsed.getJSONArray("events").toString());
        assertEquals(JSONObject.NULL, parsed.getJSONObject("settings").get("token"));
    }
}
