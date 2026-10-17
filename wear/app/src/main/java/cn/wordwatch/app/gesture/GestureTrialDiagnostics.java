package cn.wordwatch.app.gesture;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import cn.wordwatch.app.gesture.core.SensorFrame;

/** Bounded local sensor diagnostics. No learning content, credentials, network, or training import. */
final class GestureTrialDiagnostics {
    private static final java.util.concurrent.atomic.AtomicInteger runtimeSlot = new java.util.concurrent.atomic.AtomicInteger();
    private GestureTrialDiagnostics() { }

    static void write(Context context, String phase, String promptedKind, String outcome, List<SensorFrame> frames) {
        FileOutputStream stream = null;
        AtomicFile output = null;
        try {
            File root = context.getExternalFilesDir("gesture-diagnostics");
            if (root == null) throw new IllegalStateException("Local diagnostic directory unavailable");
            JSONObject record = new JSONObject();
            record.put("schemaVersion", 1);
            record.put("recordedAtMillis", System.currentTimeMillis());
            record.put("phase", phase);
            record.put("promptedKind", promptedKind);
            record.put("outcome", outcome);
            record.put("independentPhysicalLabelVerified", false);
            record.put("writesLearningRecords", false);
            JSONArray data = new JSONArray();
            long origin = frames.isEmpty() ? 0L : frames.get(0).timestampNanos();
            for (int i = 0; i < Math.min(4096, frames.size()); i++) {
                SensorFrame frame = frames.get(i);
                JSONArray row = new JSONArray();
                row.put((frame.timestampNanos() - origin) / 1_000_000.0);
                row.put(frame.accelX()); row.put(frame.accelY()); row.put(frame.accelZ());
                row.put(frame.gyroX()); row.put(frame.gyroY()); row.put(frame.gyroZ());
                data.put(row);
            }
            record.put("columns", new JSONArray(new String[]{"elapsedMs", "ax", "ay", "az", "gx", "gy", "gz"}));
            record.put("frames", data);
            // Retain only the latest of each phase; raw signals remain on this watch unless pulled via ADB.
            String file = "baseline".equals(phase) ? "latest-baseline.json"
                    : "empty-control".equals(phase) ? "latest-empty-control.json"
                    : "runtime-accepted".equals(phase) ? "latest-runtime-accepted.json"
                    : "runtime-rejected".equals(phase) ? "latest-runtime-rejected.json"
                    : "DOUBLE_PINCH_MIDDLE".equals(promptedKind) ? "latest-middle-double-pinch.json"
                    : "RUB_INDEX".equals(promptedKind) ? "latest-index-rub.json" : "latest-negative.json";
            output = new AtomicFile(new File(root, file));
            stream = output.startWrite();
            stream.write(record.toString().getBytes(StandardCharsets.UTF_8));
            output.finishWrite(stream);
            stream = null;
            if (phase.startsWith("runtime-")) {
                // Keep a bounded recent history so a later motion cannot erase the attempted gesture.
                int slot = Math.floorMod(runtimeSlot.getAndIncrement(), 16);
                output = new AtomicFile(new File(root, "runtime-" + slot + ".json"));
                stream = output.startWrite();
                stream.write(record.toString().getBytes(StandardCharsets.UTF_8));
                output.finishWrite(stream);
                stream = null;
            }
            Log.i("WordWatchGesture", phase + ": " + outcome + " (frames=" + frames.size() + ")");
        } catch (Exception failed) {
            if (output != null && stream != null) output.failWrite(stream);
            Log.w("WordWatchGesture", "Local gesture diagnostic could not be saved");
        }
    }
}
