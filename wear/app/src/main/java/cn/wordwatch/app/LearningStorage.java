package cn.wordwatch.app;

import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.UUID;

/** Full content checkpoints plus a small, generation-bound progress overlay.
 * A checkpoint always contains the complete current history. If the process dies between
 * the two files, the old overlay is either replayed against its original checkpoint or
 * ignored after a newer checkpoint has committed. Neither file is deleted for migration. */
public final class LearningStorage {
    private final AtomicFile checkpoint;
    private final AtomicFile progress;

    public LearningStorage(File directory) {
        checkpoint = new AtomicFile(new File(directory, "learning-state.json"));
        progress = new AtomicFile(new File(directory, "learning-progress.json"));
    }
    private static boolean exists(AtomicFile file) {
        return file.getBaseFile().exists() || new File(file.getBaseFile().getPath() + ".bak").exists();
    }
    public JSONObject read() throws Exception {
        if (!exists(checkpoint)) return null;
        JSONObject state = read(checkpoint);
        if (!exists(progress)) return state;
        JSONObject overlay = read(progress);
        String generation = state.optString("storageGeneration");
        // A newly committed checkpoint supersedes the entire old overlay.
        if (generation.isEmpty() || !generation.equals(overlay.optString("baseGeneration"))) return state;
        if (overlay.getInt("schemaVersion") != 1) throw new IllegalStateException("Unsupported progress format");
        JSONObject data = overlay.getJSONObject("data"), restored = shallow(state);
        for (Iterator<String> keys = data.keys(); keys.hasNext();) {
            String key = keys.next();
            if (contentKey(key) || key.equals("storageGeneration")) throw new IllegalStateException("Invalid progress field");
            restored.put(key, data.get(key));
        }
        JSONObject session = data.optJSONObject("session");
        if (session != null) {
            JSONObject frozen = state.optJSONObject("session");
            if (frozen == null || !frozen.optString("sessionId").equals(session.optString("sessionId")))
                throw new IllegalStateException("Progress does not match its frozen session");
            session = shallow(session);
            for (String key : new String[]{"pack", "words"}) if (frozen.has(key)) session.put(key, frozen.get(key));
            restored.put("session", session);
        }
        return restored;
    }
    JSONObject checkpoint(JSONObject next) throws Exception {
        JSONObject committed = shallow(next).put("storageGeneration", UUID.randomUUID().toString());
        write(checkpoint, committed);
        return committed;
    }
    JSONObject progress(JSONObject next) throws Exception {
        if (next.optString("storageGeneration").isEmpty()) return checkpoint(next);
        JSONObject data = new JSONObject();
        for (Iterator<String> keys = next.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!contentKey(key) && !key.equals("storageGeneration")) data.put(key, next.get(key));
        }
        JSONObject session = next.optJSONObject("session");
        if (session != null) data.put("session", sessionProgress(session));
        write(progress, new JSONObject().put("schemaVersion", 1)
                .put("baseGeneration", next.getString("storageGeneration")).put("data", data));
        return next;
    }
    static JSONObject sessionProgress(JSONObject session) throws Exception {
        JSONObject result = shallow(session); result.remove("pack"); result.remove("words"); return result;
    }
    private static boolean contentKey(String key) {
        return key.equals("pack") || key.equals("words") || key.equals("packages") || key.equals("withdrawn");
    }
    static JSONObject shallow(JSONObject source) throws Exception {
        JSONObject result = new JSONObject();
        if (source != null) for (Iterator<String> keys = source.keys(); keys.hasNext();) {
            String key = keys.next(); result.put(key, source.get(key));
        }
        return result;
    }
    private static JSONObject read(AtomicFile file) throws Exception {
        return new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
    }
    private static void write(AtomicFile file, JSONObject value) throws Exception {
        FileOutputStream output = null;
        try {
            output = file.startWrite(); output.write(value.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(output);
        } catch (Exception failure) {
            if (output != null) file.failWrite(output);
            throw failure;
        }
    }
}
