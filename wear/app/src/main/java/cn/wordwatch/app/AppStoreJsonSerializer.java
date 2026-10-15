package cn.wordwatch.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/** Serializes AppStore roots while reusing committed JSON for immutable content branches. */
final class AppStoreJsonSerializer {
    private static final String[] LARGE_FIELDS = {"pack", "packages", "words"};

    private JSONObject[] cachedObjects = new JSONObject[LARGE_FIELDS.length];
    private String[] cachedJson = new String[LARGE_FIELDS.length];

    Prepared prepare(JSONObject root) throws JSONException {
        JSONObject[] objects = new JSONObject[LARGE_FIELDS.length];
        String[] chunks = new String[LARGE_FIELDS.length];
        int cacheHits = 0;
        StringBuilder out = new StringBuilder();
        out.append('{');
        Iterator<String> keys = root.keys();
        boolean first = true;
        while (keys.hasNext()) {
            String key = keys.next();
            if (!first) out.append(',');
            first = false;
            out.append(JSONObject.quote(key)).append(':');

            Object value = root.opt(key);
            int slot = fieldIndex(key);
            if (slot >= 0 && value instanceof JSONObject) {
                JSONObject object = (JSONObject) value;
                objects[slot] = object;
                if (object == cachedObjects[slot] && cachedJson[slot] != null) {
                    chunks[slot] = cachedJson[slot];
                    cacheHits++;
                } else {
                    chunks[slot] = object.toString();
                    if (chunks[slot] == null) throw new JSONException("Could not serialize AppStore content branch");
                }
                out.append(chunks[slot]);
            } else {
                out.append(encodeValue(value));
            }
        }
        out.append('}');
        return new Prepared(out.toString().getBytes(StandardCharsets.UTF_8), objects, chunks, cacheHits);
    }

    void committed(Prepared prepared) {
        cachedObjects = prepared.objects.clone();
        cachedJson = prepared.chunks.clone();
    }

    void invalidate() {
        cachedObjects = new JSONObject[LARGE_FIELDS.length];
        cachedJson = new String[LARGE_FIELDS.length];
    }

    private static int fieldIndex(String key) {
        for (int i = 0; i < LARGE_FIELDS.length; i++) if (LARGE_FIELDS[i].equals(key)) return i;
        return -1;
    }

    private static String encodeValue(Object value) throws JSONException {
        String wrapped = new JSONArray().put(value).toString();
        if (wrapped == null || wrapped.length() < 2) throw new JSONException("Could not serialize AppStore value");
        return wrapped.substring(1, wrapped.length() - 1);
    }

    static final class Prepared {
        final byte[] bytes;
        final JSONObject[] objects;
        final String[] chunks;
        final int cacheHits;

        Prepared(byte[] bytes, JSONObject[] objects, String[] chunks, int cacheHits) {
            this.bytes = bytes;
            this.objects = objects;
            this.chunks = chunks;
            this.cacheHits = cacheHits;
        }
    }
}
