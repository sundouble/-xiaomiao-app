package com.xiaomiao.assistant;

import android.app.Activity;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.webkit.WebView;
import android.webkit.WebSettings;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.CHINESE);
            }
        });

        webView.addJavascriptInterface(new Object() {
            @android.webkit.JavascriptInterface
            public void speak(String text) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "xiaomiao_tts_" + System.currentTimeMillis());
            }
            @android.webkit.JavascriptInterface
            public void stop() {
                tts.stop();
            }
            @android.webkit.JavascriptInterface
            public boolean isSpeaking() {
                return tts.isSpeaking();
            }
        }, "AndroidTTS");

        webView.loadUrl("file:///android_asset/xiaomiao-v3.html");
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
