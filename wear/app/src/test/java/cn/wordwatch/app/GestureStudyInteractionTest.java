package cn.wordwatch.app;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import cn.wordwatch.app.gesture.GestureInteractionGuard;
import cn.wordwatch.app.gesture.GestureRuntime;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 35)
public final class GestureStudyInteractionTest {
    @Test public void touchDownMoveAndUpReachTheRuntimeBarrier() throws Exception {
        ActivityController<GestureStudyActivity> controller = Robolectric.buildActivity(GestureStudyActivity.class).create();
        try {
            GestureStudyActivity activity = controller.get();
            GestureInteractionGuard guard = guard(activity);
            long now = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.allows(now, now));

            dispatchTouch(activity, MotionEvent.ACTION_DOWN);
            long duringTouch = SystemClock.elapsedRealtimeNanos();
            assertFalse(guard.allows(duringTouch, duringTouch));

            dispatchTouch(activity, MotionEvent.ACTION_MOVE);
            long duringMove = SystemClock.elapsedRealtimeNanos();
            assertFalse(guard.allows(duringMove, duringMove));
            dispatchTouch(activity, MotionEvent.ACTION_UP);
            long afterRelease = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.blockedUntilNanos() > afterRelease);
            assertFalse(guard.allows(afterRelease, afterRelease));

            dispatchTouch(activity, MotionEvent.ACTION_DOWN);
            dispatchTouch(activity, MotionEvent.ACTION_CANCEL);
            long afterCancel = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.blockedUntilNanos() > afterCancel);
            assertFalse(guard.allows(afterCancel, afterCancel));
        } finally {
            controller.destroy();
        }
    }

    @Test public void keysAndRotaryScrollReachTheRuntimeBarrier() throws Exception {
        ActivityController<GestureStudyActivity> controller = Robolectric.buildActivity(GestureStudyActivity.class).create();
        try {
            GestureStudyActivity activity = controller.get();
            GestureInteractionGuard guard = guard(activity);
            long beforeKey = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.allows(beforeKey, beforeKey));

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_WAKEUP));
            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_WAKEUP));
            long afterKeepalive = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.allows(afterKeepalive, afterKeepalive));

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A));
            long afterKey = SystemClock.elapsedRealtimeNanos();
            assertFalse(guard.allows(afterKey, afterKey));

            activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A));
        } finally {
            controller.destroy();
        }

        ActivityController<GestureStudyActivity> crownController = Robolectric.buildActivity(GestureStudyActivity.class).create();
        try {
            GestureStudyActivity activity = crownController.get();
            GestureInteractionGuard guard = guard(activity);
            long beforeCrown = SystemClock.elapsedRealtimeNanos();
            assertTrue(guard.allows(beforeCrown, beforeCrown));
            long eventTime = SystemClock.uptimeMillis();
            MotionEvent crown = MotionEvent.obtain(eventTime, eventTime, MotionEvent.ACTION_SCROLL, 0, 0, 0);
            crown.setSource(InputDevice.SOURCE_ROTARY_ENCODER);
            try {
                activity.dispatchGenericMotionEvent(crown);
            } finally {
                crown.recycle();
            }
            long afterCrown = SystemClock.elapsedRealtimeNanos();
            assertFalse(guard.allows(afterCrown, afterCrown));
        } finally {
            crownController.destroy();
        }
    }

    private static void dispatchTouch(GestureStudyActivity activity, int action) {
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(eventTime, eventTime, action, 60, 60, 0);
        try {
            activity.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }

    private static GestureInteractionGuard guard(GestureStudyActivity activity) throws Exception {
        Field runtimeField = GestureStudyActivity.class.getDeclaredField("runtime");
        runtimeField.setAccessible(true);
        GestureRuntime runtime = (GestureRuntime) runtimeField.get(activity);
        Field guardField = GestureRuntime.class.getDeclaredField("interactionGuard");
        guardField.setAccessible(true);
        return (GestureInteractionGuard) guardField.get(runtime);
    }
}
