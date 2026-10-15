package cn.wordwatch.app.gesture;

import android.content.Context;
import android.util.AtomicFile;
import cn.wordwatch.app.gesture.core.GestureNoiseProfile;
import cn.wordwatch.app.gesture.core.GestureSample;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Raw calibration stays private and survives failed fitting or process death. No learning data. */
final class GestureCalibrationDraft {
    private static final int MAX_BYTES = 8_000_000;
    static final class Snapshot {
        final List<GestureSample> samples = new ArrayList<>();
        final GestureNoiseProfile noise;
        final String hand;
        final boolean emptyControlPassed;
        final GestureSensorReader.RateSnapshot rates;
        Snapshot(JSONObject json, String sensorIdentity) throws Exception {
            if (json.getInt("schema") != 1 || json.optBoolean("discarded")) throw new IllegalArgumentException("No active draft");
            if (!sensorIdentity.equals(json.getJSONObject("sensorInfo").getString("identity")))
                throw new IllegalArgumentException("Calibration belongs to another sensor");
            hand = json.getString("hand");
            if (!"left".equals(hand) && !"right".equals(hand)) throw new IllegalArgumentException("Invalid hand");
            noise = GestureNoiseProfile.fromJson(json.getJSONObject("noiseProfile").toString());
            emptyControlPassed = json.getBoolean("emptyControlPassed");
            JSONObject r = json.getJSONObject("calibrationRates");
            double a=r.getDouble("accelerometerHz"), g=r.getDouble("gyroscopeHz"), h=r.getDouble("observedHz");
            long gap=r.getLong("maxGapMillis");
            if (!(a>0 && g>0 && h>0) || !Double.isFinite(a+g+h) || gap<0)
                throw new IllegalArgumentException("Invalid measured rates");
            rates = new GestureSensorReader.RateSnapshot(a,g,h,gap,
                    r.optLong("accelerometerMaxGapMillis",gap),r.optLong("gyroscopeMaxGapMillis",gap));
            JSONArray rows=json.getJSONArray("samples");
            if(rows.length()>64) throw new IllegalArgumentException("Too many samples");
            Set<String> ids=new HashSet<>();
            for(int i=0;i<rows.length();i++) {
                GestureSample sample=GestureSample.fromJson(rows.getJSONObject(i).toString());
                if(sample.synthetic() || !ids.add(sample.fingerprint())) throw new IllegalArgumentException("Invalid calibration sample");
                samples.add(sample);
            }
        }
    }
    private static AtomicFile file(Context context) { return new AtomicFile(new File(context.getFilesDir(),"gesture-calibration-draft.json")); }
    static Snapshot read(Context context,String identity) {
        try {
            AtomicFile local=file(context);
            JSONObject json;
            if(local.getBaseFile().exists()) {try(InputStream in=local.openRead()){json=readJson(in);}}
            else {
                File external=context.getExternalFilesDir("gesture-recovery");
                if(external==null)return null;
                try(InputStream in=new FileInputStream(new File(external,"calibration-recovered.json"))){json=readJson(in);}
                Snapshot imported=new Snapshot(json,identity);
                writeJson(context,json);
                return imported;
            }
            return new Snapshot(json,identity);
        } catch(Exception invalid) {return null;}
    }
    static void write(Context context,List<GestureSample> samples,GestureNoiseProfile noise,String hand,
                      boolean empty,GestureSensorReader.SensorInfo sensors,GestureSensorReader.RateSnapshot rates) throws Exception {
        JSONObject json=new JSONObject();json.put("schema",1);json.put("savedAtMillis",System.currentTimeMillis());
        json.put("hand",hand);json.put("emptyControlPassed",empty);json.put("noiseProfile",new JSONObject(noise.toJson()));
        json.put("sensorInfo",new JSONObject().put("identity",sensors.identity));
        json.put("calibrationRates",new JSONObject().put("accelerometerHz",rates.accelerometerHz)
                .put("gyroscopeHz",rates.gyroscopeHz).put("observedHz",rates.observedHz).put("maxGapMillis",rates.maxGapMillis)
                .put("accelerometerMaxGapMillis",rates.accelerometerMaxGapMillis).put("gyroscopeMaxGapMillis",rates.gyroscopeMaxGapMillis));
        JSONArray rows=new JSONArray();for(GestureSample sample:samples)rows.put(new JSONObject(sample.toJson()));json.put("samples",rows);
        new Snapshot(json,sensors.identity);
        writeJson(context,json);
    }
    static void discard(Context context) throws Exception {writeJson(context,new JSONObject().put("schema",1).put("discarded",true));}
    private static JSONObject readJson(InputStream in) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] block=new byte[8192];int n;
        while((n=in.read(block))!=-1){if(bytes.size()+n>MAX_BYTES)throw new IOException("Draft too large");bytes.write(block,0,n);}
        return new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
    }
    private static void writeJson(Context context,JSONObject json) throws Exception {
        byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("Draft too large");
        AtomicFile target=file(context);FileOutputStream out=target.startWrite();
        try{out.write(bytes);target.finishWrite(out);}catch(Exception e){target.failWrite(out);throw e;}
    }
}
