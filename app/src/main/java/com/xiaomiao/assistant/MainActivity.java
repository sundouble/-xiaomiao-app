package com.xiaomiao.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.RecognitionListener;
import android.speech.tts.TextToSpeech;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;
import java.util.Locale;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private SpeechRecognizer recognizer;
    private RecognitionListener recognitionListener;
    private boolean recognitionAvailable;
    private static final int REQ_RECORD = 1001;

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

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest request) {
                request.grant(request.getResources());
            }
        });

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
        }

        // TTS
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) tts.setLanguage(Locale.CHINESE);
        });

        // ASR (Speech Recognition) — recognizer is recreated fresh on each startListening
        recognitionAvailable = SpeechRecognizer.isRecognitionAvailable(this);
        recognitionListener = new RecognitionListener() {
            @Override public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && matches.size() > 0) {
                    final String text = matches.get(0);
                    webView.post(() -> webView.evaluateJavascript(
                        "if(window._onSpeechResult) window._onSpeechResult('" + escapeJs(text) + "')", null));
                } else {
                    notifySpeechError(7);
                }
            }
            @Override public void onError(int error) {
                notifySpeechError(error);
            }
            @Override public void onReadyForSpeech(Bundle params) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rmsdB) {}
            @Override public void onBufferReceived(byte[] buffer) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onPartialResults(Bundle results) {}
            @Override public void onEvent(int eventType, Bundle params) {}
        };

        // Bridge: TTS + ASR
        webView.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void speak(String text) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_" + System.currentTimeMillis());
            }
            @JavascriptInterface
            public void stop() { tts.stop(); }
            @JavascriptInterface
            public boolean isSpeaking() { return tts.isSpeaking(); }

            @JavascriptInterface
            public boolean hasRecognizer() { return recognitionAvailable; }

            @JavascriptInterface
            public boolean startListening() {
                if (!recognitionAvailable) return false;
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD));
                    return false;
                }
                runOnUiThread(() -> startRecognizer());
                return true;
            }
            @JavascriptInterface
            public void stopListening() {
                if (recognizer == null) return;
                runOnUiThread(() -> {
                    try { recognizer.stopListening(); } catch (Exception ignored) {}
                });
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/xiaomiao-v3.html");
    }

    private void startRecognizer() {
        try {
            if (recognizer != null) {
                try { recognizer.destroy(); } catch (Exception ignored) {}
                recognizer = null;
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(recognitionListener);
            Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
            intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
            recognizer.startListening(intent);
        } catch (Exception e) {
            notifySpeechError(-1);
        }
    }

    private String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    private void notifySpeechError(int code) {
        webView.post(() -> webView.evaluateJavascript(
            "if(window._onSpeechError) window._onSpeechError(" + code + ")", null));
    }

    @Override
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (recognizer != null) { recognizer.destroy(); }
        super.onDestroy();
    }
}
