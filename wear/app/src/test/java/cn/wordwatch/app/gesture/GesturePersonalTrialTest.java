package cn.wordwatch.app.gesture;

import cn.wordwatch.app.gesture.core.*;
import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.*;
import static org.junit.Assert.*;

/** Generated fixtures exercise opt-in policy only; they are never physical verification evidence. */
public final class GesturePersonalTrialTest {
    @SuppressWarnings("unchecked")
    private GestureModel model(boolean synthetic, int variant) throws Exception {
        Method pattern=GestureCoreTest.class.getDeclaredMethod("pattern",GestureKind.class,int.class,double.class,long.class);
        pattern.setAccessible(true);
        List<GestureSample> samples=new ArrayList<>();
        for(GestureKind kind:GestureKind.values()) {
            int count=kind==GestureKind.UNKNOWN?10:6;
            for(int i=0;i<count;i++) {
                List<SensorFrame> frames=(List<SensorFrame>)pattern.invoke(null,kind,i+variant,1.0+0.01*i,0L);
                samples.add(GestureSample.of(kind,frames,"unit-policy-fixture",synthetic));
            }
        }
        List<SensorFrame> still=new ArrayList<>();
        for(int i=0;i<81;i++)still.add(new SensorFrame(1_000_000_000L+i*50_000_000L,0,0,0,0,0,0));
        return GestureModel.train(samples,GestureNoiseProfile.fromStillFrames(still));
    }
    private GestureProfileStore.Snapshot snapshot(GestureModel model) {
        GestureProfileVerifier verifier=new GestureProfileVerifier(model);
        return new GestureProfileStore.Snapshot(model.toJson(),verifier.toJson(),verifier.profile().toJson(),
                model.revision(),"fixture-sensor","unit fixture",107,107,107,22,"left",0,false);
    }
    @Test public void explicitTrialPermitsCandidateWithoutInventingVerification() throws Exception {
        GestureModel model=model(false,0);GestureVerificationProfile evidence=new GestureProfileVerifier(model).profile();
        GestureProfileStore.Snapshot original=snapshot(model);
        assertFalse(original.permitsLearning(model,evidence));
        GestureProfileStore.Snapshot trial=GestureProfileStore.Snapshot.fromJson(original.withPersonalTrial().toJson());
        assertTrue(trial.enabled);assertTrue(trial.personalTrial);assertTrue(trial.permitsLearning(model,evidence));
        assertEquals(0,trial.verifiedAtEpochMillis);assertFalse(evidence.isProductionVerifiedFor(model));
        assertEquals(original.verificationJson,trial.verificationJson);
    }
    @Test public void legacyProfileCannotSilentlyEnableTrial() throws Exception {
        GestureModel model=model(false,0);JSONObject legacy=snapshot(model).toJson();
        legacy.put("schema",2);legacy.remove("personalTrial");
        GestureProfileStore.Snapshot restored=GestureProfileStore.Snapshot.fromJson(legacy);
        assertFalse(restored.personalTrial);
        assertFalse(restored.withEnabled(true).permitsLearning(model,new GestureProfileVerifier(model).profile()));
    }
    @Test public void disablingOrRecalibratingClearsTrialOptIn() throws Exception {
        GestureModel model=model(false,0);GestureProfileStore.Snapshot trial=snapshot(model).withPersonalTrial();
        assertFalse(trial.withEnabled(false).personalTrial);
        GestureProfileStore.Snapshot pending=trial.withCalibrationPending(true);
        assertFalse(pending.enabled);assertFalse(pending.personalTrial);
        try { pending.withPersonalTrial();fail("pending calibration enabled"); }catch(IllegalStateException expected) { }
    }
    @Test public void syntheticOrMismatchedModelsCannotUsePersonalTrial() throws Exception {
        GestureModel synthetic=model(true,0);
        assertFalse(snapshot(synthetic).withPersonalTrial().permitsLearning(synthetic,new GestureProfileVerifier(synthetic).profile()));
        GestureModel model=model(false,0), different=model(false,20);
        assertFalse(snapshot(model).withPersonalTrial().permitsLearning(different,new GestureProfileVerifier(different).profile()));
    }
    @Test public void knownInvalidEvidenceStillBlocksTrial() throws Exception {
        GestureModel model=model(false,0);GestureProfileVerifier verifier=new GestureProfileVerifier(model);
        verifier.recordMissingHeldOut(GestureKind.DOUBLE_PINCH_MIDDLE,false,"unit-policy-fixture");
        assertTrue(verifier.profile().failClosed());
        assertFalse(snapshot(model).withPersonalTrial().permitsLearning(model,verifier.profile()));
    }
}
