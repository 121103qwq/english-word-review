package cn.wordwatch.app.render;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import cn.wordwatch.app.GestureStudyActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

/** Layout-only synthetic cards; no sensor verification or real study state is created. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 35, shadows = ShadowAtomicFile.class)
public final class GestureStudyRenderTest {
    @Test @Config(qualifiers = "w192dp-h192dp-round-watch")
    public void frontBackAndCompletionFitSmallRoundWatch() throws Exception { renderStates("192", 1.0f); }

    @Test @Config(qualifiers = "w227dp-h227dp-round-watch")
    public void longMeaningAndLargeTextWrapOnRoundWatch() throws Exception { renderStates("227-large", 1.3f); }

    private void renderStates(String suffix, float fontScale) throws Exception {
        RuntimeEnvironment.setFontScale(fontScale);
        ActivityController<GestureStudyActivity> controller = Robolectric.buildActivity(GestureStudyActivity.class).create();
        GestureStudyActivity activity = controller.get();
        set(activity, "foreground", true);
        JSONObject word = new JSONObject().put("id", "synthetic").put("word", "understand")
                .put("phonetic", "/ˌʌndəˈstænd/").put("meaning", "理解；了解；这是一段用于检查圆屏换行的较长释义")
                .put("examples", new JSONArray().put(new JSONObject().put("en", "I understand the question.").put("zh", "我理解这道题。")));
        JSONObject session = new JSONObject().put("sessionId", "render-only")
                .put("queue", new JSONArray().put(new JSONObject().put("wordId", "synthetic")))
                .put("words", new JSONObject().put("synthetic", word)).put("position", 0).put("revealed", false);
        draw(activity, session, false, "gesture-front-" + suffix);
        assertTrue(contains(activity.getWindow().getDecorView(), "翻面看答案"));
        Object scrollBefore = fieldValue(activity, "scroll");
        Object revealBefore = fieldValue(activity, "revealButton");
        Object wordBefore = fieldValue(activity, "wordView");
        set(activity, "busy", true);
        set(activity, "pendingReveal", true);
        draw(activity, session, false, "gesture-reveal-saving-" + suffix);
        assertFalse("showing the answer early must not pretend the write is committed", session.optBoolean("revealed"));
        assertTrue(contains(activity.getWindow().getDecorView(), "知道 · 下一词"));
        assertFalse(((Button) fieldValue(activity, "rememberButton")).isEnabled());
        assertNull("saving cannot accept another card event", fieldValue(activity, "context"));
        set(activity, "busy", false);
        set(activity, "pendingReveal", false);
        session.put("revealed", true);
        draw(activity, session, true, "gesture-back-" + suffix);
        assertSame(scrollBefore, fieldValue(activity, "scroll"));
        assertSame(revealBefore, fieldValue(activity, "revealButton"));
        assertSame(wordBefore, fieldValue(activity, "wordView"));
        assertFalse(contains(activity.getWindow().getDecorView(), "翻面看答案"));
        assertTrue(contains(activity.getWindow().getDecorView(), "知道 · 下一词"));
        assertTrue(contains(activity.getWindow().getDecorView(), "不清楚 · 下一词"));
        session.put("position", 1);
        draw(activity, session, true, "gesture-complete-" + suffix);
        assertTrue(contains(activity.getWindow().getDecorView(), "撤销上一词"));
        controller.destroy();
        RuntimeEnvironment.setFontScale(1.0f);
    }

    private static void draw(GestureStudyActivity activity, JSONObject session, boolean undo, String name) throws Exception {
        Class<?> stateClass = Class.forName("cn.wordwatch.app.GestureStudyActivity$ViewState");
        Constructor<?> constructor = stateClass.getDeclaredConstructor(JSONObject.class, boolean.class, String.class);
        constructor.setAccessible(true);
        set(activity, "current", constructor.newInstance(session, undo, ""));
        Method render = GestureStudyActivity.class.getDeclaredMethod("renderCard"); render.setAccessible(true); render.invoke(activity);
        View view = activity.getWindow().getDecorView();
        int width = activity.getResources().getDisplayMetrics().widthPixels;
        int height = activity.getResources().getDisplayMetrics().heightPixels;
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
        check(view, activity.getResources().getDisplayMetrics().density);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap); Path circle = new Path();
        circle.addCircle(width / 2f, height / 2f, Math.min(width, height) / 2f, Path.Direction.CW);
        canvas.clipPath(circle); view.draw(canvas);
        File output = new File(System.getProperty("wordwatch.renderDir"), name + ".png");
        output.getParentFile().mkdirs();
        try (FileOutputStream stream = new FileOutputStream(output)) { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)); }
    }

    private static Object fieldValue(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(View view, float density) {
        if (view.getVisibility() == View.GONE) return;
        if (view instanceof Button) assertTrue("48 dp touch target", view.getMeasuredHeight() >= Math.round(48 * density));
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            if (label.getLayout() != null) for (int i = 0; i < label.getLineCount(); i++) assertEquals(0, label.getLayout().getEllipsisCount(i));
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) check(((ViewGroup) view).getChildAt(i), density);
    }
    private static boolean contains(View view, String text) {
        if (view.getVisibility() != View.VISIBLE) return false;
        if (view instanceof TextView && text.contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            if (contains(((ViewGroup) view).getChildAt(i), text)) return true;
        return false;
    }
}
