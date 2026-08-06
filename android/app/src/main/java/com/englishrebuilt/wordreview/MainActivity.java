package com.englishrebuilt.wordreview;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(android.os.Bundle savedInstanceState) {
        registerPlugin(EnglishReviewNativePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
