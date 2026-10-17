package cn.wordwatch.app.gesture;

import cn.wordwatch.app.gesture.core.GestureKind;
import cn.wordwatch.app.gesture.core.SensorFrame;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import static org.junit.Assert.*;

/** Synthetic orchestration tests only; no hardware result or learning record is produced. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GestureSetupFlowTest {
    @Test public void cleanEmptyControlUnlocksTrainingWithoutAddingPositiveSample() throws Exception {
        checkEmptyControl(false, 0, true, true);
    }

    @Test public void movementDuringEmptyControlCannotBeCountedAsTrainingSuccess() throws Exception {
        checkEmptyControl(false, 1, true, false);
    }

    @Test public void touchOrMissingSensorDataCannotPassEmptyControl() throws Exception {
        checkEmptyControl(true, 0, true, false);
        checkEmptyControl(false, 0, false, false);
    }

    @Test public void feedbackViewExistsForActualLatencyMeasurement() throws Exception {
        ActivityController<GestureSetupActivity> controller = Robolectric.buildActivity(GestureSetupActivity.class).create();
        try { assertNotNull(get(controller.get(), "stateView")); }
        finally { controller.destroy(); }
    }

    @Test public void eitherGestureCanBeCollectedOnceEmptyControlPasses() throws Exception {
        ActivityController<GestureSetupActivity> controller = Robolectric.buildActivity(GestureSetupActivity.class).create();
        try {
            GestureSetupActivity activity = controller.get();
            set(activity, "stage", 2);
            set(activity, "emptyControlPassed", true);
            Method render = GestureSetupActivity.class.getDeclaredMethod("render");
            render.setAccessible(true);
            render.invoke(activity);
            View root = activity.findViewById(android.R.id.content);
            assertTrue(hasText(root, "采集一次中指双捏"));
            assertTrue(hasText(root, "采集一次食指搓动"));
            assertFalse(hasText(root, "样本齐全，进入日常动作样本"));
        } finally { controller.destroy(); }
    }

    private static boolean hasText(View view, String expected) {
        if (view instanceof TextView && expected.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (hasText(group.getChildAt(i), expected)) return true;
        }
        return false;
    }

    @Test public void emptyControlCountdownNeverAsksForAGesture() throws Exception {
        ActivityController<GestureSetupActivity> controller = Robolectric.buildActivity(GestureSetupActivity.class).create();
        try {
            GestureSetupActivity activity = controller.get();
            long now = SystemClock.elapsedRealtimeNanos();
            set(activity, "trialOpen", true);
            set(activity, "emptyControlTrial", true);
            set(activity, "trialId", 1L);
            set(activity, "trialStartedAtNs", now - 3_000_000_000L);
            set(activity, "activityResumed", true);
            set(activity, "windowFocused", true);
            set(activity, "foreground", true);
            set(activity, "interactiveCache", true);
            set(activity, "interactiveCacheTimestampNs", now);
            TextView timer = new TextView(activity);
            set(activity, "timerView", timer);
            Method update = GestureSetupActivity.class.getDeclaredMethod("updateTrialTimer", long.class, long.class);
            update.setAccessible(true);
            update.invoke(activity, 1L, now + 5_000_000_000L);
            assertTrue(timer.getText().toString().contains("保持不动"));
            assertFalse(timer.getText().toString().contains("做手势"));
        } finally { controller.destroy(); }
    }

    @SuppressWarnings("unchecked")
    private static void checkEmptyControl(boolean touched, int candidates, boolean complete, boolean expected) throws Exception {
        ActivityController<GestureSetupActivity> controller = Robolectric.buildActivity(GestureSetupActivity.class).create();
        GestureSetupActivity activity = controller.get();
        try {
            set(activity, "stage", 2);
            set(activity, "trialOpen", true);
            set(activity, "emptyControlTrial", true);
            set(activity, "trialInterfered", touched);
            set(activity, "qualifyingCandidates", candidates);
            set(activity, "trialExpected", GestureKind.UNKNOWN);
            set(activity, "trialId", 1L);
            long start = 10_000_000_000L;
            set(activity, "trialStartedAtNs", start);
            set(activity, "trialDeadlineNs", start + 8_000_000_000L);
            List<SensorFrame> frames = (List<SensorFrame>) get(activity, "trialFrames");
            if (complete) for (int i = 1; i < 800; i++) {
                frames.add(new SensorFrame(start + i * 10_000_000L, 0, 0, 9.8, 0, 0, 0));
            }
            Method finish = GestureSetupActivity.class.getDeclaredMethod("finishTrial", long.class);
            finish.setAccessible(true);
            finish.invoke(activity, 1L);
            assertEquals(expected, get(activity, "emptyControlPassed"));
            assertEquals(0, ((List<?>) get(activity, "trainingSamples")).size());
            assertArrayEquals(new int[]{0, 0}, (int[]) get(activity, "trainingCounts"));
            assertFalse((Boolean) get(activity, "trialOpen"));
        } finally { controller.destroy(); }
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
