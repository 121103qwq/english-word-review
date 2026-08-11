package com.englishrebuilt.wordreview;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(EnglishReviewNativePlugin.class);
        super.onCreate(savedInstanceState);
        // Custom MP3 and generated TTS audio can start when the app advances to
        // a new question, not only from a direct screen tap.
        getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
    }
}
