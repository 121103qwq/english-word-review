package com.englishrebuilt.wordreview;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.speech.tts.TextToSpeech;
import android.util.Base64;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

@CapacitorPlugin(name = "EnglishReviewNative")
public class EnglishReviewNativePlugin extends Plugin {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS_PREFIX = "english-review:";
    private static final String PREFERENCES = "english-review-secure-values";

    private SharedPreferences preferences() {
        return getContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private String alias(String key) {
        return ALIAS_PREFIX + key;
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
        if (text == null) {
            call.reject("Missing text");
            return;
        }
        final TextToSpeech[] engine = new TextToSpeech[1];
        engine[0] = new TextToSpeech(getContext(), status -> {
            if (status != TextToSpeech.SUCCESS) {
                call.reject("Android text-to-speech initialization failed");
                return;
            }
            engine[0].setLanguage(Locale.US);
            engine[0].setSpeechRate(requestedRate.floatValue());
            int result = engine[0].speak(text, TextToSpeech.QUEUE_FLUSH, null, "english-review");
            if (result == TextToSpeech.ERROR) call.reject("Android text-to-speech failed");
            else call.resolve();
        });
    }
}
