package com.xiaomiao.assistant;

import android.app.Activity;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.webkit.WebView;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.PermissionRequest;
import android.webkit.JavascriptInterface;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.net.Uri;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private TextToSpeech tts;
    private static final int REQ_RECORD = 1001;

    // Volcengine ASR
    private String volcApiKey = "";
    private AudioRecord audioRecord;
    private Thread recordThread;
    private volatile boolean recording = false;
    private ByteArrayOutputStream pcmData;

    private static final int SAMPLE_RATE = 16000;
    private static final int MAX_RECORD_MS = 30000;
    private static final int SILENCE_STOP_MS = 3000;
    private static final int NO_SPEECH_MS = 8000;
    private static final int SOUND_THRESHOLD = 400;

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

        // Bridge: TTS + cloud ASR (Volcengine)
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
            public void setVolcApiKey(String key) {
                volcApiKey = key == null ? "" : key.trim();
            }
            @JavascriptInterface
            public boolean hasVolcConfig() {
                return !volcApiKey.isEmpty();
            }

            @JavascriptInterface
            public String getRecognizerInfo() {
                return "识别方式:火山引擎云端"
                    + " | 火山配置:" + (hasVolcConfig() ? "已填" : "未填!")
                    + " | 录音权限:" + (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED ? "已允许" : "未允许");
            }

            @JavascriptInterface
            public boolean startListening() {
                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    runOnUiThread(() -> {
                        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_RECORD);
                        } else {
                            try {
                                Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                                startActivity(i);
                            } catch (Exception ignored) {}
                        }
                    });
                    return false;
                }
                startRecording();
                return true;
            }
            @JavascriptInterface
            public void stopListening() {
                recording = false;
            }
        }, "AndroidBridge");

        webView.loadUrl("file:///android_asset/xiaomiao-v3.html");
    }

    private void startRecording() {
        stopAudioRecord();
        pcmData = new ByteArrayOutputStream();
        int bufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(bufSize, 4096));
        } catch (Exception e) {
            notifyAsrFail("无法启动录音: " + e.getMessage());
            return;
        }
        recording = true;
        audioRecord.startRecording();
        notifySpeechEvent("ready");

        recordThread = new Thread(() -> {
            byte[] buf = new byte[1280]; // ~40ms per chunk at 16kHz 16bit mono
            long startTime = System.currentTimeMillis();
            long silenceMs = 0;
            boolean hadSound = false;
            while (recording) {
                int n = audioRecord.read(buf, 0, buf.length);
                if (n <= 0) continue;
                synchronized (this) {
                    if (pcmData != null) pcmData.write(buf, 0, n);
                }
                int max = 0;
                for (int i = 0; i + 1 < n; i += 2) {
                    int v = Math.abs((buf[i] & 0xff) | (buf[i + 1] << 8));
                    if (v > max) max = v;
                }
                long elapsed = System.currentTimeMillis() - startTime;
                if (max > SOUND_THRESHOLD) { hadSound = true; silenceMs = 0; }
                else { silenceMs += 40; }
                if (elapsed > MAX_RECORD_MS) break;
                if (hadSound && silenceMs > SILENCE_STOP_MS) break;
                if (!hadSound && elapsed > NO_SPEECH_MS) break;
            }
            recording = false;
            final boolean heard = hadSound;
            runOnUiThread(() -> {
                notifySpeechEvent("end");
                finishRecording(heard);
            });
        });
        recordThread.start();
    }

    private void stopAudioRecord() {
        recording = false;
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            try { audioRecord.release(); } catch (Exception ignored) {}
            audioRecord = null;
        }
    }

    private void finishRecording(boolean heard) {
        byte[] pcm;
        synchronized (this) {
            pcm = pcmData == null ? new byte[0] : pcmData.toByteArray();
            pcmData = null;
        }
        stopAudioRecord();
        if (!heard || pcm.length < SAMPLE_RATE) { // less than ~0.3s of audio
            notifyAsrFail("没有听到声音，靠近一点再试一次？");
            return;
        }
        recognize(pcm);
    }

    private void recognize(byte[] pcm) {
        new Thread(() -> {
            try {
                String b64 = Base64.encodeToString(buildWav(pcm), Base64.NO_WRAP);
                JSONObject body = new JSONObject();
                body.put("user", new JSONObject().put("uid", "xiaomiao"));
                body.put("audio", new JSONObject().put("data", b64));
                body.put("request", new JSONObject().put("model_name", "bigmodel"));

                HttpURLConnection conn = (HttpURLConnection) new URL(
                    "https://openspeech.bytedance.com/api/v3/auc/bigmodel/recognize/flash").openConnection();
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("X-Api-Key", volcApiKey);
                conn.setRequestProperty("X-Api-Resource-Id", "volc.bigasr.auc_turbo");
                conn.setRequestProperty("X-Api-Request-Id", UUID.randomUUID().toString());
                conn.setRequestProperty("X-Api-Sequence", "-1");
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();

                int httpCode = conn.getResponseCode();
                String statusCode = conn.getHeaderField("X-Api-Status-Code");
                InputStream is = httpCode == 200 ? conn.getInputStream() : conn.getErrorStream();
                String resp = readAll(is);

                if ("20000000".equals(statusCode)) {
                    JSONObject j = new JSONObject(resp);
                    JSONObject result = j.optJSONObject("result");
                    String text = result == null ? "" : result.optString("text", "").trim();
                    if (!text.isEmpty()) notifyResult(text);
                    else notifyAsrFail("没有识别出文字，再说一次？");
                } else if ("20000003".equals(statusCode)) {
                    notifyAsrFail("没有听清你的声音，大声一点再说一次？");
                } else {
                    String msg = conn.getHeaderField("X-Api-Message");
                    if (msg == null || msg.isEmpty()) msg = resp.substring(0, Math.min(150, resp.length()));
                    notifyAsrFail("火山引擎返回错误(" + (statusCode != null ? statusCode : String.valueOf(httpCode)) + ")：" + msg);
                }
                conn.disconnect();
            } catch (Exception e) {
                notifyAsrFail("识别请求出错: " + e.getMessage());
            }
        }).start();
    }

    private byte[] buildWav(byte[] pcm) {
        ByteBuffer buf = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(new byte[]{'R','I','F','F'});
        buf.putInt(pcm.length + 36);
        buf.put(new byte[]{'W','A','V','E'});
        buf.put(new byte[]{'f','m','t',' '});
        buf.putInt(16);
        buf.putShort((short) 1);
        buf.putShort((short) 1);
        buf.putInt(SAMPLE_RATE);
        buf.putInt(SAMPLE_RATE * 2);
        buf.putShort((short) 2);
        buf.putShort((short) 16);
        buf.put(new byte[]{'d','a','t','a'});
        buf.putInt(pcm.length);
        buf.put(pcm);
        return buf.array();
    }

    private String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) out.write(buf, 0, n);
        is.close();
        return out.toString("UTF-8");
    }

    private String escapeJs(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n");
    }

    private void notifyResult(String text) {
        webView.post(() -> webView.evaluateJavascript(
            "if(window._onSpeechResult) window._onSpeechResult('" + escapeJs(text) + "')", null));
    }

    private void notifyAsrFail(String msg) {
        webView.post(() -> webView.evaluateJavascript(
            "if(window._onAsrFail) window._onAsrFail('" + escapeJs(msg) + "')", null));
    }

    private void notifySpeechEvent(String ev) {
        webView.post(() -> webView.evaluateJavascript(
            "if(window._onSpeechEvent) window._onSpeechEvent('" + ev + "')", null));
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD) {
            final boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            webView.post(() -> webView.evaluateJavascript(
                "if(window._onPermissionResult) window._onPermissionResult(" + granted + ")", null));
        }
    }

    @Override
    protected void onDestroy() {
        stopAudioRecord();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        super.onDestroy();
    }
}
