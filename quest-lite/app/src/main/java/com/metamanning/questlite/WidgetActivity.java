package com.metamanning.questlite;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.webkit.*;
import android.widget.*;
import android.net.Uri;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;

/** HTML runs inside a sandboxed frame in a separate process/profile. Only its own bounded JSON state is exposed. */
@android.annotation.TargetApi(34)
public final class WidgetActivity extends WorkspacePanelActivity {
    private static final int IMPORT_HTML = 61, EXPORT_HTML = 62;
    private String html;
    private volatile boolean network;
    private int revision;
    private final java.util.concurrent.ExecutorService documents = java.util.concurrent.Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved); initialize("widgets"); if (windowId == null) return;
        html = getIntent().getStringExtra("widget_html"); if (html == null) html = "<h2>New widget</h2><p>Choose Edit or Templates to get started.</p>";
        network = getIntent().getBooleanExtra("widget_network", false);
        LinearLayout bar = toolbar();
        button(bar, "Edit", this::edit); button(bar, "Templates", this::templates); button(bar, "Options", this::options); button(bar, "Windows", this::manager);
        web = new WebView(this);
        WebSettings settings = web.getSettings(); settings.setJavaScriptEnabled(true); settings.setDomStorageEnabled(false);
        settings.setAllowFileAccess(false); settings.setAllowContentAccess(false); settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        StateBridge state = new StateBridge();
        web.addJavascriptInterface(state, "LumaState");
        web.addJavascriptInterface(state, "MetamanningState");
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if ("about:srcdoc".equals(request.getUrl().toString()) || "about:blank".equals(request.getUrl().toString())) return false;
                message("Links stay inside the widget sandbox. Use the workspace Browser for websites."); return true;
            }
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                if ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme)) return null;
                if (network && "https".equals(scheme)) return null;
                return new WebResourceResponse("text/plain", "UTF-8", new ByteArrayInputStream(new byte[0]));
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onPermissionRequest(PermissionRequest request) { request.deny(); }
            @Override public boolean onConsoleMessage(ConsoleMessage console) {
                if (console.messageLevel() == ConsoleMessage.MessageLevel.ERROR) message("Widget error: " + console.message().substring(0, Math.min(160, console.message().length())));
                return true;
            }
        });
        attachWeb(); render();
    }
    public final class StateBridge {
        @JavascriptInterface public String loadState() { return getSharedPreferences("widget-data", MODE_PRIVATE).getString(windowId, "{}"); }
        @JavascriptInterface public boolean saveState(String value) {
            if (!WidgetStatePolicy.valid(value)) return false;
            getSharedPreferences("widget-data", MODE_PRIVATE).edit().putString(windowId, value).apply(); return true;
        }
    }
    private void render() {
        try {
            web.getSettings().setBlockNetworkLoads(!network || hidden || !resumed);
            web.loadDataWithBaseURL("https://widgets.metamanning.invalid/", WidgetDocument.render(html, network), "text/html", "UTF-8", null);
            message(network ? "Widget · HTTPS network access on" : "Widget · offline · local state saved on this headset");
        } catch (Exception e) { message("Could not display widget: " + e.getMessage()); }
    }
    private void persistSource() {
        revision++;
        Bundle values = new Bundle(); values.putString("html", html); values.putBoolean("network", network); event("widget_updated", values);
    }
    private void edit() {
        EditText editor = new EditText(this); editor.setText(html); editor.setMinLines(12); editor.setMaxLines(20);
        editor.setGravity(android.view.Gravity.TOP); editor.setTextSize(14);
        editor.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setTypeface(android.graphics.Typeface.MONOSPACE);
        editor.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(WidgetDocument.MAX_HTML_BYTES)});
        ScrollView scroll = new ScrollView(this); scroll.addView(editor);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Widget HTML / CSS / JavaScript").setView(scroll).setPositiveButton("Run", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String value = editor.getText().toString();
            if (value.getBytes(StandardCharsets.UTF_8).length > WidgetDocument.MAX_HTML_BYTES) { editor.setError("Maximum HTML size is 128 KB"); return; }
            html = value; persistSource(); render(); dialog.dismiss();
        })); dialog.show();
    }
    private void templates() {
        try (InputStream stream = getAssets().open("widget-templates.json")) {
            JSONArray items = new JSONArray(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
            String[] titles = new String[items.length()]; for (int i = 0; i < titles.length; i++) titles[i] = items.getJSONObject(i).getString("title");
            new AlertDialog.Builder(this).setTitle("Choose a widget template").setItems(titles, (dialog, which) -> {
                try {
                    String replacement = items.getJSONObject(which).getString("html");
                    new AlertDialog.Builder(this).setTitle("Replace this widget's code?").setMessage("Its local saved data will remain until you reset it.")
                        .setPositiveButton("Replace", (d, w) -> { html = replacement; persistSource(); render(); }).setNegativeButton("Cancel", null).show();
                } catch (Exception e) { message("Template could not be loaded."); }
            }).show();
        } catch (Exception e) { message("Templates could not be loaded."); }
    }
    private void options() {
        String[] actions = {network ? "Turn network access off" : "Allow HTTPS network access", "Import HTML file", "Export HTML file", "Reset this widget's saved data"};
        new AlertDialog.Builder(this).setTitle("Widget options").setItems(actions, (dialog, which) -> {
            if (which == 0) {
                if (network) { network = false; persistSource(); render(); }
                else new AlertDialog.Builder(this).setTitle("Allow network access?").setMessage("This widget's HTML and scripts will be able to contact HTTPS websites. Camera, microphone and ChatGPT account access remain unavailable.")
                    .setPositiveButton("Allow HTTPS", (d, w) -> { network = true; persistSource(); render(); }).setNegativeButton("Cancel", null).show();
            } else if (which == 1) chooseDocument(false);
            else if (which == 2) chooseDocument(true);
            else new AlertDialog.Builder(this).setTitle("Reset widget data?").setMessage("Clear only this widget's local saved state. Its code and other widgets stay intact.")
                .setPositiveButton("Reset", (d, w) -> { getSharedPreferences("widget-data", MODE_PRIVATE).edit().remove(windowId).apply(); render(); }).setNegativeButton("Cancel", null).show();
        }).show();
    }
    private void chooseDocument(boolean export) {
        Intent intent = new Intent(export ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(export ? "text/html" : "*/*");
        if (export) intent.putExtra(Intent.EXTRA_TITLE, "luma-widget.html");
        else intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"text/html", "text/plain", "application/xhtml+xml"});
        try { startActivityForResult(intent, export ? EXPORT_HTML : IMPORT_HTML); } catch (android.content.ActivityNotFoundException e) { message("No document picker is available."); }
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data); Uri uri = data == null ? null : data.getData(); if (result != RESULT_OK || uri == null) return;
        final int expectedRevision = revision;
        final String exportSource = html;
        documents.execute(() -> {
            try {
                if (request == IMPORT_HTML) try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IOException("Cannot read file");
                    byte[] bytes = stream.readNBytes(WidgetDocument.MAX_HTML_BYTES + 1);
                    if (bytes.length > WidgetDocument.MAX_HTML_BYTES) throw new IOException("HTML file exceeds 128 KB");
                    String imported = new String(bytes, StandardCharsets.UTF_8);
                    runOnUiThread(() -> {
                        if (destroyed) return;
                        if (revision != expectedRevision) { message("Import canceled because the widget changed while the file was opening."); return; }
                        html = imported; persistSource(); render();
                    });
                }
                if (request == EXPORT_HTML) try (OutputStream stream = getContentResolver().openOutputStream(uri)) {
                    if (stream == null) throw new IOException("Cannot write file"); stream.write(exportSource.getBytes(StandardCharsets.UTF_8));
                    runOnUiThread(() -> message("Widget HTML exported."));
                }
            } catch (Exception e) { runOnUiThread(() -> message("File operation failed: " + e.getMessage())); }
        });
    }
    @Override protected void onDestroy() { documents.shutdownNow(); super.onDestroy(); }
    @Override protected void visibilityChanged(boolean visible) {
        if (web != null) {
            boolean previouslyBlocked = web.getSettings().getBlockNetworkLoads();
            boolean allowed = network && visible && resumed && !hidden;
            web.getSettings().setBlockNetworkLoads(!allowed);
            if (allowed && previouslyBlocked) web.reload();
        }
    }
    @Override protected void onWorkspaceControl(String command, Intent intent) {
        if ("rename".equals(command)) message(intent.getStringExtra("title"));
    }
}
