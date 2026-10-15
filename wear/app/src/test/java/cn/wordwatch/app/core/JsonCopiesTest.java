package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class JsonCopiesTest {
    @Test public void copiesDetachEveryContainerAndPreserveJsonValuesWithoutSerialization() throws Exception {
        JSONObject original = new JSONObject() {
            @Override public String toString() { throw new AssertionError("must not serialize to copy"); }
        };
        original.put("nested", new JSONArray().put(new JSONObject().put("label", "你好\n\"quoted\"")));
        original.put("null", JSONObject.NULL).put("long", Long.MAX_VALUE).put("fraction", 1.25).put("boolean", true);
        JSONObject copied = JsonCopies.object(original);
        assertEquals("你好\n\"quoted\"", copied.getJSONArray("nested").getJSONObject(0).getString("label"));
        copied.getJSONArray("nested").getJSONObject(0).put("label", "changed");
        copied.getJSONArray("nested").put(false);
        assertEquals("你好\n\"quoted\"", original.getJSONArray("nested").getJSONObject(0).getString("label"));
        assertEquals(1, original.getJSONArray("nested").length());
        assertTrue(copied.has("null")); assertTrue(copied.isNull("null"));
        assertEquals(Long.MAX_VALUE, copied.getLong("long"));
        assertEquals(1.25, copied.getDouble("fraction"), 0.0);
        assertTrue(copied.getBoolean("boolean"));
        assertNull(JsonCopies.object(null)); assertNull(JsonCopies.array(null));
    }
}
