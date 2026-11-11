package kr.haeyang.gifstudio;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String HOME = "https://chocoboy1923.github.io/gift-trasfrom-final/";
    private static final int PICK_FILES = 10, SAVE_GIF = 11;
    private static final long MAX_BYTES = 512L * 1024 * 1024;
    private WebView web;
    private FrameLayout root;
    private LinearLayout errorPanel;
    private ProgressBar progress;
    private View videoView;
    private WebChromeClient.CustomViewCallback videoCallback;
    private ValueCallback<Uri[]> fileCallback;
    private final ExecutorService disk = Executors.newSingleThreadExecutor();
    private boolean saving, destroyed, bridgeAvailable;
    private long expected, written;
    // The stream and document are accessed only on the single disk executor.
    private OutputStream output;
    private Uri document;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        root = new FrameLayout(this);
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                view.setPadding(cutout == null ? 0 : cutout.getSafeInsetLeft(),
                    cutout == null ? 0 : cutout.getSafeInsetTop(),
                    cutout == null ? 0 : cutout.getSafeInsetRight(),
                    cutout == null ? 0 : cutout.getSafeInsetBottom());
                return insets;
            });
        }
        root.setBackgroundColor(Color.rgb(243, 248, 253));
        web = new WebView(this);
        web.setKeepScreenOn(true);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(-1, 6);
        root.addView(progress, bar);
        errorPanel = new LinearLayout(this);
        errorPanel.setOrientation(LinearLayout.VERTICAL);
        errorPanel.setGravity(android.view.Gravity.CENTER);
        errorPanel.setPadding(40, 40, 40, 40);
        errorPanel.setBackgroundColor(Color.rgb(243, 248, 253));
        TextView message = new TextView(this);
        message.setText("사이트에 연결할 수 없습니다.\n인터넷 연결을 확인한 뒤 다시 시도해 주세요.");
        message.setGravity(android.view.Gravity.CENTER);
        message.setTextSize(18);
        errorPanel.addView(message);
        Button retry = new Button(this);
        retry.setText("다시 연결");
        retry.setOnClickListener(v -> web.loadUrl(HOME));
        errorPanel.addView(retry);
        root.addView(errorPanel, new FrameLayout.LayoutParams(-1, -1));
        errorPanel.setVisibility(View.GONE);
        setContentView(root);
        configureWebView();
        immersive();
        web.loadUrl(HOME);
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setSupportZoom(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (bridgeAvailable) {
            WebViewCompat.addWebMessageListener(web, "GifNative",
                Collections.singleton("https://chocoboy1923.github.io"),
                (view, message, sourceOrigin, isMainFrame, reply) -> {
                    if (!isMainFrame || !trusted(view.getUrl())) return;
                    try {
                        JSONObject data = new JSONObject(message.getData());
                        String type = data.getString("type");
                        if (type.equals("offer")) {
                            if (saving) { reply.postMessage("busy"); return; }
                            long size = data.getLong("size");
                            if (size <= 0 || size > MAX_BYTES) {
                                reply.postMessage("error"); toast("GIF는 512MB 이하만 저장할 수 있습니다."); return;
                            }
                            String name = data.optString("name", "haeyang.gif")
                                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_");
                            if (name.length() > 100) name = name.substring(0, 100);
                            if (!name.toLowerCase(Locale.ROOT).endsWith(".gif")) name += ".gif";
                            expected = size;
                            saving = true;
                            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                .addCategory(Intent.CATEGORY_OPENABLE).setType("image/gif")
                                .putExtra(Intent.EXTRA_TITLE, name);
                            try { startActivityForResult(intent, SAVE_GIF); }
                            catch (android.content.ActivityNotFoundException e) {
                                saving = false; reply.postMessage("error"); toast("파일 저장 앱을 찾을 수 없습니다.");
                            }
                        } else if (type.equals("chunk") && saving) {
                            String chunk = data.getString("data");
                            if (chunk.length() > 65536) throw new IllegalArgumentException("Chunk too large");
                            disk.execute(() -> {
                                try {
                                    if (output == null) throw new IllegalStateException("No destination");
                                    byte[] bytes = Base64.decode(chunk, Base64.NO_WRAP);
                                    if (written + bytes.length > expected) throw new IllegalStateException("Size mismatch");
                                    output.write(bytes); written += bytes.length;
                                    runOnUiThread(() -> { if (!destroyed) reply.postMessage("next"); });
                                } catch (Exception e) { failSave(); }
                            });
                        } else if (type.equals("end") && saving) {
                            disk.execute(() -> {
                                try {
                                    if (output == null || written != expected) throw new IllegalStateException("Incomplete GIF");
                                    output.close(); output = null; document = null;
                                    runOnUiThread(() -> {
                                        saving = false;
                                        if (!destroyed) { reply.postMessage("saved"); toast("GIF를 저장했습니다."); }
                                    });
                                } catch (Exception e) { failSave(); }
                            });
                        } else if (type.equals("error")) { disk.execute(this::failSave); }
                    } catch (Exception e) {
                        reply.postMessage("error");
                        if (saving) disk.execute(this::failSave);
                    }
                });
        }
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (trusted(uri.toString())) return false;
                if ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()) || "mailto".equals(uri.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); }
                    catch (android.content.ActivityNotFoundException e) { toast("링크를 열 앱을 찾을 수 없습니다."); }
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                if (!trusted(url)) { view.stopLoading(); view.loadUrl(HOME); return; }
                errorPanel.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                if (saving) disk.execute(MainActivity.this::failSave);
            }
            @Override public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (trusted(url) && bridgeAvailable) {
                    try (java.io.InputStream in = getAssets().open("download.js")) {
                        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
                        byte[] chunk = new byte[4096];
                        int length;
                        while ((length = in.read(chunk)) != -1) buffer.write(chunk, 0, length);
                        view.evaluateJavascript(buffer.toString(StandardCharsets.UTF_8.name()), null);
                    } catch (Exception e) { toast("저장 기능을 불러오지 못했습니다. 앱을 다시 실행해 주세요."); }
                }
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                if (req.isForMainFrame()) showError();
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse response) {
                if (req.isForMainFrame()) showError();
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); }
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"})
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try { startActivityForResult(intent, PICK_FILES); }
                catch (android.content.ActivityNotFoundException e) {
                    fileCallback.onReceiveValue(null); fileCallback = null; toast("파일 선택 앱을 찾을 수 없습니다.");
                }
                return true;
            }
            @Override public void onShowCustomView(View view, CustomViewCallback callback) {
                if (videoView != null) { callback.onCustomViewHidden(); return; }
                videoView = view; videoCallback = callback;
                root.addView(view, new FrameLayout.LayoutParams(-1, -1)); immersive();
            }
            @Override public void onHideCustomView() { hideVideo(); }
        });
        web.setDownloadListener((url, agent, disposition, mime, length) -> {
            if (bridgeAvailable && trusted(web.getUrl()) && url.startsWith("blob:")) {
                web.evaluateJavascript("window.GifDownloads && window.GifDownloads.offer(" + JSONObject.quote(url) + ", 'haeyang.gif')", null);
            } else { toast("GIF 저장에는 최신 Android System WebView가 필요합니다."); }
        });
    }

    private boolean trusted(String url) {
        if (url == null) return false;
        Uri uri = Uri.parse(url);
        return "https".equals(uri.getScheme()) && "chocoboy1923.github.io".equals(uri.getHost())
            && (uri.getPort() == -1 || uri.getPort() == 443) && uri.getUserInfo() == null
            && uri.getPath() != null && uri.getPath().startsWith("/gift-trasfrom-final/");
    }
    private void showError() { progress.setVisibility(View.GONE); errorPanel.setVisibility(View.VISIBLE); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private void script(String code) { if (!destroyed) web.evaluateJavascript(code, null); }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PICK_FILES && fileCallback != null) {
            ArrayList<Uri> files = new ArrayList<>();
            if (result == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < Math.min(80, data.getClipData().getItemCount()); i++) {
                        Uri uri = data.getClipData().getItemAt(i).getUri();
                        if ("content".equals(uri.getScheme())) files.add(uri);
                    }
                } else if (data.getData() != null && "content".equals(data.getData().getScheme())) files.add(data.getData());
            }
            fileCallback.onReceiveValue(files.isEmpty() ? null : files.toArray(new Uri[0])); fileCallback = null;
        } else if (request == SAVE_GIF) {
            if (result != RESULT_OK || data == null || data.getData() == null || !saving) {
                saving = false; script("window.GifDownloads?.cancel()"); return;
            }
            Uri uri = data.getData();
            disk.execute(() -> {
                try {
                    document = uri; written = 0;
                    output = getContentResolver().openOutputStream(uri, "w");
                    if (output == null) throw new java.io.IOException("Cannot open document");
                    runOnUiThread(() -> script("window.GifDownloads?.start()"));
                } catch (Exception e) { failSave(); }
            });
        }
        immersive();
    }
    private void failSave() {
        try { if (output != null) output.close(); } catch (Exception ignored) { }
        output = null;
        try { if (document != null) DocumentsContract.deleteDocument(getContentResolver(), document); } catch (Exception ignored) { }
        document = null;
        runOnUiThread(() -> {
            saving = false;
            if (!destroyed) { script("window.GifDownloads?.cancel()"); toast("저장하지 못했습니다. 다시 시도해 주세요."); }
        });
    }
    private void hideVideo() {
        if (videoView == null) return;
        root.removeView(videoView); videoView = null;
        videoCallback.onCustomViewHidden(); videoCallback = null; immersive();
    }
    @SuppressWarnings("deprecation") private void immersive() {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(true);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) immersive(); }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if (videoView != null) hideVideo();
        else if (web.canGoBack()) web.goBack();
        else new android.app.AlertDialog.Builder(this).setMessage("앱을 종료할까요?")
            .setNegativeButton("취소", null).setPositiveButton("종료", (d, w) -> finish()).show();
    }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override protected void onPause() { if (web != null) web.onPause(); super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true;
        if (fileCallback != null) { fileCallback.onReceiveValue(null); fileCallback = null; }
        disk.execute(this::failSave); disk.shutdown();
        if (web != null) { root.removeView(web); web.destroy(); }
        super.onDestroy();
    }
}
