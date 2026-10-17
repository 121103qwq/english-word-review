package cn.wordwatch.app.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Detached JSON trees without allocating and reparsing a full intermediate JSON string. */
public final class JsonCopies {
    private JsonCopies() { }

    public static JSONObject object(JSONObject source) {
        if (source == null) return null;
        JSONObject result = new JSONObject();
        try {
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, value(source.get(key)));
            }
            return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException("Invalid JSON tree", invalid); }
    }

    public static JSONArray array(JSONArray source) {
        if (source == null) return null;
        JSONArray result = new JSONArray();
        try {
            for (int i = 0; i < source.length(); i++) result.put(value(source.get(i)));
            return result;
        } catch (JSONException invalid) { throw new IllegalArgumentException("Invalid JSON tree", invalid); }
    }

    private static Object value(Object source) {
        if (source instanceof JSONObject) return object((JSONObject) source);
        if (source instanceof JSONArray) return array((JSONArray) source);
        // JSON scalars (String, Number, Boolean and JSONObject.NULL) are immutable.
        return source;
    }
}
