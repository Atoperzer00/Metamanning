package com.metamanning.questlite;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.CookieManager;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

/** Small account-based companion to the imported JarvisVR Unity client.
 * No API backend, credential extraction, injected scripts, or embedded keys.
 */
public final class MainActivity extends Activity {
    private static final int PHOTO = 10, MICROPHONE = 11;
    private WebView web;
    private ValueCallback<Uri[]> photoResult;
    private PermissionRequest pendingMic;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout toolbar = new LinearLayout(this);
        addButton(toolbar, "Back", () -> { if (web.canGoBack()) web.goBack(); });
        addButton(toolbar, "ChatGPT", () -> web.loadUrl("https://chatgpt.com/"));
        addButton(toolbar, "Browser", () -> openBrowser("https://chatgpt.com/"));
        layout.addView(toolbar);
        web = new WebView(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android document picker supplies content URIs.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (trustedNavigation(uri)) return false;
                if (request.isForMainFrame() && "https".equals(uri.getScheme())) openBrowser(uri.toString());
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                finishPhoto(null);
                photoResult = callback;
                Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                pick.addCategory(Intent.CATEGORY_OPENABLE);
                pick.setType("image/*");
                pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try { startActivityForResult(pick, PHOTO); }
                catch (ActivityNotFoundException e) {
                    finishPhoto(null);
                    notice("No photo picker available. Try ChatGPT in Browser.");
                }
                return true;
            }
            @Override public void onPermissionRequest(PermissionRequest request) {
                runOnUiThread(() -> requestMicrophone(request));
            }
            @Override public void onPermissionRequestCanceled(PermissionRequest request) {
                if (pendingMic == request) pendingMic = null;
            }
        });
        if (state == null || web.restoreState(state) == null) web.loadUrl("https://chatgpt.com/");
    }

    private static boolean trustedNavigation(Uri uri) {
        if (!"https".equals(uri.getScheme())) return false;
        String host = uri.getHost();
        return "chatgpt.com".equals(host) || "openai.com".equals(host)
            || (host != null && (host.endsWith(".chatgpt.com") || host.endsWith(".openai.com")));
    }

    private void requestMicrophone(PermissionRequest request) {
        // Only the official ChatGPT origin may request audio, never video or arbitrary resources.
        if (!"https".equals(request.getOrigin().getScheme()) || !"chatgpt.com".equals(request.getOrigin().getHost())) {
            request.deny(); return;
        }
        boolean audio = false;
        for (String resource : request.getResources())
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) audio = true;
        if (!audio) { request.deny(); return; }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        } else {
            if (pendingMic != null) { request.deny(); return; }
            pendingMic = request;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(code, permissions, grants);
        if (code != MICROPHONE || pendingMic == null) return;
        PermissionRequest request = pendingMic;
        pendingMic = null;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else { request.deny(); notice("Microphone denied. Keyboard input is still available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PHOTO) finishPhoto(WebChromeClient.FileChooserParams.parseResult(result, data));
    }
    private void finishPhoto(Uri[] uris) {
        if (photoResult != null) { photoResult.onReceiveValue(uris); photoResult = null; }
    }
    private void addButton(LinearLayout bar, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setMinHeight((int)(56 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(v -> action.run());
        bar.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void openBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { notice("No browser installed."); }
    }
    private void notice(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); super.onSaveInstanceState(out); }
    @Override protected void onPause() { web.onPause(); CookieManager.getInstance().flush(); super.onPause(); }
    @Override protected void onResume() { super.onResume(); if (web != null) web.onResume(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        finishPhoto(null);
        if (pendingMic != null) { pendingMic.deny(); pendingMic = null; }
        web.destroy();
        super.onDestroy();
    }
}
