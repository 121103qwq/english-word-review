package com.englishrebuilt.wordreview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.speech.tts.TextToSpeech;
import android.util.Base64;

import androidx.activity.result.ActivityResult;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Iterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@CapacitorPlugin(name = "EnglishReviewNative")
public class EnglishReviewNativePlugin extends Plugin {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "english-review:";
    private static final String PREFERENCES = "english-review-secure-values";
    private TextToSpeech textToSpeech;
    private boolean textToSpeechInitializing;
    private final List<PendingSpeech> pendingSpeech = new ArrayList<>();

    private static final class PendingSpeech {
        final PluginCall call;
        final String text;
        final String locale;
        final float rate;

        PendingSpeech(PluginCall call, String text, String locale, float rate) {
            this.call = call;
            this.text = text;
            this.locale = locale;
            this.rate = rate;
        }
    }

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private String alias(String key) {
        return ALIAS_PREFIX + key;
    }

    @PluginMethod
    public void saveTextFile(PluginCall call) {
        String filename = call.getString("filename");
        String content = call.getString("content");
        String mimeType = call.getString("mimeType", "text/plain");
        if (filename == null || filename.trim().isEmpty() || content == null) {
            call.reject("Missing filename or content");
            return;
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(mimeType)
            .putExtra(Intent.EXTRA_TITLE, filename);
        startActivityForResult(call, intent, "handleSaveTextFileResult");
    }

    @ActivityCallback
    private void handleSaveTextFileResult(PluginCall call, ActivityResult activityResult) {
        if (call == null) return;

        Uri uri = activityResult.getData() == null ? null : activityResult.getData().getData();
        if (activityResult.getResultCode() != Activity.RESULT_OK || uri == null) {
            JSObject result = new JSObject();
            result.put("saved", false);
            call.resolve(result);
            return;
        }

        String content = call.getString("content");
        new Thread(() -> {
            try (OutputStream output = getContext().getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IllegalStateException("Unable to open selected file");
                output.write(content.getBytes(StandardCharsets.UTF_8));
                JSObject result = new JSObject();
                result.put("saved", true);
                result.put("path", uri.toString());
                call.resolve(result);
            } catch (Exception error) {
                call.reject("Unable to save file", error);
            }
        }).start();
    }

    private SecretKey getOrCreateKey(String key) throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        KeyStore.Entry existing = keyStore.getEntry(alias(key), null);
        if (existing instanceof KeyStore.SecretKeyEntry) {
            return ((KeyStore.SecretKeyEntry) existing).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
            alias(key),
            KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
        return generator.generateKey();
    }

    @PluginMethod
    public void saveSecret(PluginCall call) {
        String key = call.getString("key");
        String value = call.getString("value");
        if (key == null || value == null) {
            call.reject("Missing key or value");
            return;
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(key));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String encoded = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":" +
                Base64.encodeToString(ciphertext, Base64.NO_WRAP);
            preferences().edit().putString(key, encoded).apply();
            call.resolve();
        } catch (Exception error) {
            call.reject("Unable to save credential", error);
        }
    }

    @PluginMethod
    public void loadSecret(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("Missing key");
            return;
        }
        JSObject result = new JSObject();
        String encoded = preferences().getString(key, null);
        if (encoded == null) {
            result.put("value", null);
            call.resolve(result);
            return;
        }
        try {
            String[] pieces = encoded.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                Cipher.DECRYPT_MODE,
                getOrCreateKey(key),
                new GCMParameterSpec(128, Base64.decode(pieces[0], Base64.NO_WRAP))
            );
            byte[] plaintext = cipher.doFinal(Base64.decode(pieces[1], Base64.NO_WRAP));
            result.put("value", new String(plaintext, StandardCharsets.UTF_8));
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Unable to read credential", error);
        }
    }

    @PluginMethod
    public void deleteSecret(PluginCall call) {
        String key = call.getString("key");
        if (key == null) {
            call.reject("Missing key");
            return;
        }
        try {
            preferences().edit().remove(key).apply();
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(alias(key))) keyStore.deleteEntry(alias(key));
            call.resolve();
        } catch (Exception error) {
            call.reject("Unable to delete credential", error);
        }
    }

    @PluginMethod
    public void speak(PluginCall call) {
        String text = call.getString("text");
        Double requestedRate = call.getDouble("rate", 0.85);
        String locale = call.getString("locale", "en-US");
        if (text == null || text.trim().isEmpty()) {
            call.reject("Missing text");
            return;
        }
        PendingSpeech request = new PendingSpeech(
            call,
            text.trim(),
            locale,
            Math.max(0.5f, Math.min(2f, requestedRate.floatValue()))
        );
        getActivity().runOnUiThread(() -> enqueueSpeech(request));
    }

    private void enqueueSpeech(PendingSpeech request) {
        if (textToSpeechInitializing) {
            pendingSpeech.add(request);
            return;
        }
        if (textToSpeech != null) {
            performSpeech(request);
            return;
        }
        pendingSpeech.add(request);
        textToSpeechInitializing = true;
        textToSpeech = new TextToSpeech(getContext().getApplicationContext(), status -> {
            textToSpeechInitializing = false;
            if (status != TextToSpeech.SUCCESS) {
                TextToSpeech failedEngine = textToSpeech;
                textToSpeech = null;
                if (failedEngine != null) failedEngine.shutdown();
                for (PendingSpeech pending : pendingSpeech) {
                    pending.call.reject("Android text-to-speech initialization failed");
                }
                pendingSpeech.clear();
                return;
            }

            // QUEUE_FLUSH means only the newest request should be audible when
            // several taps arrive while Android is initializing its TTS engine.
            int lastIndex = pendingSpeech.size() - 1;
            for (int index = 0; index < lastIndex; index++) pendingSpeech.get(index).call.resolve();
            PendingSpeech latest = lastIndex >= 0 ? pendingSpeech.get(lastIndex) : null;
            pendingSpeech.clear();
            if (latest != null) performSpeech(latest);
        });
    }

    private void performSpeech(PendingSpeech request) {
        Locale requestedLocale = Locale.forLanguageTag(request.locale.replace('_', '-'));
        if (requestedLocale.getLanguage().isEmpty()) requestedLocale = Locale.US;
        int languageResult = textToSpeech.setLanguage(requestedLocale);
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            request.call.reject("English text-to-speech voice is unavailable on this device");
            return;
        }
        textToSpeech.setSpeechRate(request.rate);
        int result = textToSpeech.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, "english-review");
        if (result == TextToSpeech.ERROR) request.call.reject("Android text-to-speech failed");
        else request.call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        for (PendingSpeech pending : pendingSpeech) pending.call.reject("Android text-to-speech stopped");
        pendingSpeech.clear();
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
            textToSpeech = null;
        }
        textToSpeechInitializing = false;
        super.handleOnDestroy();
    }

    @PluginMethod
    public void httpRequest(PluginCall call) {
        JSObject request = call.getObject("request");
        if (request == null) {
            call.reject("Missing request");
            return;
        }
        new Thread(() -> {
            try {
                String url = request.getString("url");
                String method = request.getString("method", "GET");
                int timeout = request.getInteger("timeoutMs", 12000);
                if (url == null) throw new IllegalArgumentException("Missing URL");
                OkHttpClient client = new OkHttpClient.Builder()
                    .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                    .readTimeout(timeout, TimeUnit.MILLISECONDS)
                    .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build();
                Request.Builder builder = new Request.Builder().url(url);
                JSObject headers = request.getJSObject("headers");
                if (headers != null) {
                    Iterator<String> keys = headers.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        builder.header(key, String.valueOf(headers.get(key)));
                    }
                }
                String bodyBase64 = request.getString("bodyBase64");
                String bodyText = request.getString("body");
                byte[] body = null;
                if (bodyBase64 != null || bodyText != null) {
                    body = bodyBase64 != null
                        ? Base64.decode(bodyBase64, Base64.DEFAULT)
                        : bodyText.getBytes(StandardCharsets.UTF_8);
                }
                RequestBody requestBody = null;
                if (body != null) {
                    String contentType = headers == null ? null : headers.getString("Content-Type");
                    requestBody = RequestBody.create(body, contentType == null ? null : MediaType.parse(contentType));
                }
                builder.method(method, requestBody);
                try (Response response = client.newCall(builder.build()).execute()) {
                    byte[] responseBytes = response.body() == null ? new byte[0] : response.body().bytes();
                    JSObject responseHeaders = new JSObject();
                    for (Map.Entry<String, List<String>> entry : response.headers().toMultimap().entrySet()) {
                        responseHeaders.put(entry.getKey().toLowerCase(Locale.ROOT), String.join(", ", entry.getValue()));
                    }
                    JSObject result = new JSObject();
                    result.put("status", response.code());
                    result.put("headers", responseHeaders);
                    result.put(
                        "body",
                        "base64".equals(request.getString("responseType"))
                            ? Base64.encodeToString(responseBytes, Base64.NO_WRAP)
                            : new String(responseBytes, StandardCharsets.UTF_8)
                    );
                    call.resolve(result);
                }
            } catch (Exception error) {
                call.reject("Native HTTP request failed", error);
            }
        }).start();
    }
}
