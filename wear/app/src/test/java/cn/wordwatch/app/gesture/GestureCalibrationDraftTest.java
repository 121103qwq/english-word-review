package cn.wordwatch.app.gesture;

import android.content.Context;
import cn.wordwatch.app.gesture.core.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35, shadows = cn.wordwatch.app.render.ShadowAtomicFile.class)
public final class GestureCalibrationDraftTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final GestureSensorReader.SensorInfo sensors=new GestureSensorReader.SensorInfo(null,null);
    private final GestureSensorReader.RateSnapshot rates=new GestureSensorReader.RateSnapshot(107,107,107,21,21,21);
    private List<SensorFrame> frames() {
        List<SensorFrame> frames=new ArrayList<>();
        for(int i=0;i<400;i++)frames.add(new SensorFrame(1_000_000_000L+i*10_000_000L,0,0,9.8,0,0,0));
        return frames;
    }
    private GestureSample sample() {return GestureSample.of(GestureKind.UNKNOWN,frames(),"test-draft-fixture",false);}
    private void write(List<GestureSample> samples) throws Exception {
        GestureCalibrationDraft.write(context,samples,GestureNoiseProfile.fromStillFrames(frames()),"left",true,sensors,rates);
    }
    @Test public void rawDraftRoundTripsWithoutModelAndSurvivesReadAgain() throws Exception {
        GestureSample sample=sample();write(Collections.singletonList(sample));
        for(int i=0;i<2;i++) {
            GestureCalibrationDraft.Snapshot saved=GestureCalibrationDraft.read(context,sensors.identity);
            assertNotNull(saved);assertEquals(1,saved.samples.size());
            assertEquals(sample.fingerprint(),saved.samples.get(0).fingerprint());
            assertEquals("left",saved.hand);assertTrue(saved.emptyControlPassed);
            assertEquals(107,saved.rates.observedHz,0);
        }
    }
    @Test public void sensorMismatchAndCorruptDraftCannotRestore() throws Exception {
        write(Collections.singletonList(sample()));
        assertNull(GestureCalibrationDraft.read(context,"different-sensor"));
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),"gesture-calibration-draft.json"))) {
            out.write("{broken".getBytes(StandardCharsets.UTF_8));
        }
        assertNull(GestureCalibrationDraft.read(context,sensors.identity));
    }
    @Test public void duplicateSampleCannotReplacePreviousValidDraft() throws Exception {
        GestureSample sample=sample();write(Collections.singletonList(sample));
        try {write(Arrays.asList(sample,sample));fail("duplicate accepted");}catch(IllegalArgumentException expected) { }
        assertEquals(1,GestureCalibrationDraft.read(context,sensors.identity).samples.size());
    }
    @Test public void discardPreventsImportingAnOldRecoveryFile() throws Exception {
        write(Collections.singletonList(sample()));
        File source=new File(context.getFilesDir(),"gesture-calibration-draft.json");
        File recovery=new File(context.getExternalFilesDir("gesture-recovery"),"calibration-recovered.json");
        try(InputStream in=new FileInputStream(source);OutputStream out=new FileOutputStream(recovery)) {
            byte[] block=new byte[8192];int n;while((n=in.read(block))!=-1)out.write(block,0,n);
        }
        GestureCalibrationDraft.discard(context);
        assertNull(GestureCalibrationDraft.read(context,sensors.identity));
    }
}
