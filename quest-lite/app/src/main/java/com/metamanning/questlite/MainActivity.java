package com.metamanning.questlite;

import android.Manifest;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognizerIntent;
import android.util.Base64;
import android.view.TextureView;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.TextView;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.content.SharedPreferences;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.ScrollView;
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
 * Uses a visible website UI adapter, without private APIs, credential access or embedded keys.
 */
public final class MainActivity extends Activity {
    private static final int PHOTO = 10, MICROPHONE = 11, CAMERA = 12, DICTATION = 13, PICK_PHOTO = 14;
    private static final String HEADSET_CAMERA = "horizonos.permission.HEADSET_CAMERA";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor();
    private QuestCamera camera;
    private TextureView preview;
    private TextView status, windowTitle;
    private String adapter;
    private boolean live, staged, destroyed;
    private int capturePurpose, sentFrames, liveGeneration;
    private long captureDeadline;
    private boolean panelHidden;
    private String liveUrl;
    private int intervalSeconds = 15, frameLimit = 20, textZoom = 100;
    private String livePrompt = "Describe important changes in this latest camera view. Keep the answer brief.";
    private final BroadcastReceiver workspaceControl = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String id = windowId();
            if (id == null || !id.equals(intent.getStringExtra("window_id"))) return;
            String command = intent.getStringExtra("command");
            if ("close".equals(command)) { stopLive(); camera.close(); finish(); }
            else if ("pause".equals(command)) { panelHidden = true; stopLive(); camera.close(); web.onPause(); showStatus("Hidden: camera and live sharing stopped."); }
            else if ("resume".equals(command)) { panelHidden = false; web.onResume(); }
            else if ("draft".equals(command)) {
                String prompt = intent.getStringExtra("prompt");
                if (prompt != null && prompt.length() <= 16000) offerDraft(prompt);
            }
            else if ("rename".equals(command) && windowTitle != null) {
                String title = intent.getStringExtra("title");
                if (title != null) windowTitle.setText(title.substring(0, Math.min(title.length(), 60)));
            }
        }
    };
    private final Runnable liveTick = new Runnable() {
        @Override public void run() {
            if (!live) return;
            if (!isChatPage() || !camera.ready()) { stopLive(); showStatus("Live stopped: camera or conversation unavailable."); return; }
            if (sentFrames >= frameLimit) { stopLive(); showStatus("Live session limit reached. Tap Live to start another session."); return; }
            if (!staged && capturePurpose == 0) { capturePurpose = 2; captureDeadline = System.currentTimeMillis() + 10000; camera.capture(); }
            handler.postDelayed(this, intervalSeconds * 1000L);
        }
    };
    private final Runnable sendTick = new Runnable() {
        @Override public void run() {
            if (!live) return;
            if (capturePurpose == 2 && System.currentTimeMillis() > captureDeadline) {
                stopLive(); showStatus("Live stopped: camera capture timed out."); return;
            }
            if (staged) evaluate("sendPending()", result -> {
                if (!live) return;
                if ("sent".equals(result)) { staged = false; sentFrames++; showStatus("LIVE · sent " + sentFrames + "/" + frameLimit + " camera images · Stop to end"); }
                else if (!"waiting".equals(result)) { stopLive(); showStatus("Live stopped: " + result + ". Check the ChatGPT attachment UI."); }
            });
            handler.postDelayed(this, 750);
        }
    };
    private WebView web;
    private ValueCallback<Uri[]> photoResult;
    private PermissionRequest pendingMic;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences options = accountOptions();
        intervalSeconds = Math.max(5, Math.min(60, options.getInt("interval", 15)));
        frameLimit = Math.max(1, Math.min(100, options.getInt("limit", 20)));
        textZoom = Math.max(80, Math.min(160, options.getInt("zoom", 100)));
        livePrompt = options.getString("prompt", livePrompt);
        if (getIntent().getBooleanExtra("spatial_panel", false)) {
            panelHidden = getIntent().getBooleanExtra("initial_hidden", false);
            if (android.os.Build.VERSION.SDK_INT >= 33)
                registerReceiver(workspaceControl, new IntentFilter(getPackageName() + ".WORKSPACE_CONTROL"), Context.RECEIVER_NOT_EXPORTED);
        }
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        ShellStyle.header(this, layout);
        if (getIntent().getBooleanExtra("spatial_panel", false)) {
            windowTitle = new TextView(this);
            String title = getIntent().getStringExtra("window_title");
            windowTitle.setText(title == null ? "Conversation" : title.substring(0, Math.min(title.length(), 60)));
            windowTitle.setTextColor(android.graphics.Color.parseColor("#91F4D4"));
            windowTitle.setTextSize(15); windowTitle.setPadding(8, 0, 8, 8); layout.addView(windowTitle);
        }
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(ShellStyle.dp(this, 12) + insets.getSystemWindowInsetLeft(), ShellStyle.dp(this, 6) + insets.getSystemWindowInsetTop(),
                ShellStyle.dp(this, 12) + insets.getSystemWindowInsetRight(), ShellStyle.dp(this, 8) + insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout toolbar = new LinearLayout(this);
        addButton(toolbar, "Back", () -> { if (web.canGoBack()) web.goBack(); });
        addButton(toolbar, "ChatGPT", () -> web.loadUrl("https://chatgpt.com/"));
        addButton(toolbar, "Browser", () -> openBrowser("https://chatgpt.com/"));
        addButton(toolbar, "Options", this::options);
        if (getIntent().getBooleanExtra("spatial_panel", false))
            addButton(toolbar, "Windows", () -> workspaceEvent("manager", null));
        layout.addView(toolbar);
        LinearLayout inputBar = new LinearLayout(this);
        addButton(inputBar, "Keyboard", this::keyboard);
        addButton(inputBar, "Speak", this::dictate);
        addButton(inputBar, "Photo", this::pickPhoto);
        if (getIntent().getBooleanExtra("spatial_panel", false)) addButton(inputBar, "Tools", this::tools);
        layout.addView(inputBar);
        LinearLayout cameraBar = new LinearLayout(this);
        addButton(cameraBar, "Camera", this::startCamera);
        addButton(cameraBar, "Snapshot", () -> {
            if (live) { showStatus("Stop Live before attaching a manual snapshot."); return; }
            if (!camera.ready()) { showStatus("Open Camera first."); return; }
            capturePurpose = 1; camera.capture();
        });
        addButton(cameraBar, "Live", this::confirmLive);
        addButton(cameraBar, "Stop", () -> { stopLive(); camera.close(); preview.setVisibility(View.GONE); showStatus("Camera and live sharing stopped."); });
        layout.addView(cameraBar);
        status = new TextView(this);
        status.setText(R.string.initial_status);
        ShellStyle.status(this, status, getString(R.string.initial_status));
        status.setPadding(ShellStyle.dp(this, 12), ShellStyle.dp(this, 10), ShellStyle.dp(this, 12), ShellStyle.dp(this, 10));
        LinearLayout.LayoutParams statusLayout = new LinearLayout.LayoutParams(-1, -2);
        statusLayout.setMargins(0, ShellStyle.dp(this, 4), 0, ShellStyle.dp(this, 8));
        layout.addView(status, statusLayout);
        preview = new TextureView(this);
        preview.setVisibility(View.GONE);
        layout.addView(preview, new LinearLayout.LayoutParams(-1, (int)(160 * getResources().getDisplayMetrics().density)));
        camera = new QuestCamera(this, preview, new QuestCamera.Listener() {
            @Override public void status(String value) { if (live && !camera.ready()) stopLive(); showStatus(value); }
            @Override public void frame(byte[] jpeg) {
                int purpose = capturePurpose; capturePurpose = 0;
                if (purpose == 0 || (purpose == 2 && !live)) return;
                attachImage(jpeg, purpose == 2);
            }
        });
        try (InputStream source = getAssets().open("chatgpt-adapter.js")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = source.read(block)) != -1) bytes.write(block, 0, count);
            adapter = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) { adapter = ""; showStatus("Website adapter could not load."); }
        web = new WebView(this);
        layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(layout);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setTextZoom(textZoom);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android document picker supplies content URIs.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true);
        web.setWebViewClient(new WebViewClient() {
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                stopLive();
                if (pendingMic != null) { pendingMic.deny(); pendingMic = null; }
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
                workspaceEvent("url", url);
                if (live && liveUrl != null && !liveUrl.equals(url)) {
                    Uri old = Uri.parse(liveUrl), next = Uri.parse(url);
                    if ((old.getPath() == null || "/".equals(old.getPath())) && next.getPath() != null && next.getPath().startsWith("/c/") && staged && sentFrames <= 1) liveUrl = url;
                    else { stopLive(); showStatus("Live stopped because the conversation changed."); }
                }
            }
            @Override public void onPageFinished(WebView view, String url) {
                if (isChatPage()) web.evaluateJavascript(adapter, null);
            }
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
        if (state == null || web.restoreState(state) == null) {
            String initial = getIntent().getStringExtra("initial_url");
            web.loadUrl(initial != null && officialUrl(initial) ? initial : "https://chatgpt.com/");
        }
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
        if (code == CAMERA) {
            if (cameraAllowed()) chooseCamera();
            else showStatus("Headset camera permission denied. Photo upload remains available.");
            return;
        }
        if (code != MICROPHONE || pendingMic == null) return;
        PermissionRequest request = pendingMic;
        pendingMic = null;
        if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
            request.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
        else { request.deny(); notice("Microphone denied. Keyboard input is still available."); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == PHOTO) {
            if (result == RESULT_OK && data != null && data.getClipData() != null) {
                Uri[] uris = new Uri[data.getClipData().getItemCount()];
                for (int i = 0; i < uris.length; i++) uris[i] = data.getClipData().getItemAt(i).getUri();
                finishPhoto(uris);
            } else finishPhoto(WebChromeClient.FileChooserParams.parseResult(result, data));
        }
        if (request == DICTATION && result == RESULT_OK && data != null) {
            ArrayList<String> words = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (words != null && !words.isEmpty()) evaluate("dictate(" + JSONObject.quote(words.get(0)) + ")", resultText -> showStatus("ok".equals(resultText) ? "Dictation inserted. Review and press Send." : resultText));
        }
        if (request == PICK_PHOTO && result == RESULT_OK && data != null && data.getData() != null) importPhoto(data.getData());
    }
    private boolean isChatPage() {
        if (web == null || web.getUrl() == null) return false;
        Uri uri = Uri.parse(web.getUrl());
        return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost());
    }
    private interface Result { void receive(String result); }
    private void evaluate(String action, Result callback) {
        if (!isChatPage()) { callback.receive("Open and sign into ChatGPT first."); return; }
        web.evaluateJavascript("(() => { try { return window.metamanning ? window.metamanning." + action +
            " : 'adapter-unavailable'; } catch(e) { return 'adapter-error'; } })()", value -> {
            if (destroyed) return;
            try { callback.receive(new org.json.JSONTokener(value).nextValue().toString()); }
            catch (Exception e) { callback.receive("adapter-error"); }
        });
    }
    private void showStatus(String value) {
        value = value.replace("authentication-required", "sign into ChatGPT first")
            .replace("missing-composer", "wait for the ChatGPT message box to load")
            .replace("missing-file-input", "open ChatGPT's attachment menu first")
            .replace("adapter-unavailable", "reload ChatGPT to reconnect the controls")
            .replace("adapter-error", "the ChatGPT page controls could not be used");
        if (!destroyed && status != null) ShellStyle.status(this, status, value);
    }
    private void keyboard() {
        evaluate("focus()", result -> {
            if (!"ok".equals(result)) { showStatus(result); return; }
            web.requestFocus();
            ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(web, InputMethodManager.SHOW_IMPLICIT);
        });
    }
    private void dictate() {
        if (live) { showStatus("Stop Live to dictate a message."); return; }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your ChatGPT message");
        try { startActivityForResult(intent, DICTATION); }
        catch (ActivityNotFoundException e) {
            evaluate("startDictation()", result -> showStatus("dictation-started".equals(result)
                ? "ChatGPT dictation opened. Speak, then review the transcript."
                : "No Android speech service or compatible website dictation control. Use the website microphone or keyboard."));
        }
    }
    private void pickPhoto() {
        if (live) { showStatus("Stop Live to choose a photo."); return; }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE);
        try { startActivityForResult(intent, PICK_PHOTO); }
        catch (ActivityNotFoundException e) { showStatus("No photo picker installed. Try the website's attachment control."); }
    }
    private void importPhoto(Uri uri) {
        showStatus("Preparing photo…");
        imageWorker.execute(() -> {
            try {
                ByteArrayOutputStream input = new ByteArrayOutputStream();
                try (InputStream stream = getContentResolver().openInputStream(uri)) {
                    if (stream == null) throw new IllegalStateException("Cannot open selected photo");
                    byte[] block = new byte[8192]; int count;
                    while ((count = stream.read(block)) != -1) {
                        if (input.size() + count > 20 * 1024 * 1024) throw new IllegalStateException("Photo exceeds 20 MB");
                        input.write(block, 0, count);
                    }
                }
                byte[] bytes = input.toByteArray();
                BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (options.outWidth <= 0 || options.outHeight <= 0) throw new IllegalStateException("Unsupported image");
                options.inSampleSize = 1;
                while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1600) options.inSampleSize *= 2;
                options.inJustDecodeBounds = false;
                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
                if (bitmap == null) throw new IllegalStateException("Cannot decode photo");
                bitmap = PhotoOrientation.correct(bitmap, bytes);
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output); bitmap.recycle();
                runOnUiThread(() -> { if (!destroyed) attachImage(output.toByteArray(), false); });
            } catch (Exception e) { runOnUiThread(() -> showStatus("Photo failed: " + e.getMessage())); }
        });
    }
    private boolean cameraAllowed() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(HEADSET_CAMERA) == PackageManager.PERMISSION_GRANTED;
    }
    private void startCamera() {
        if (live) stopLive();
        preview.setVisibility(View.VISIBLE);
        if (!cameraAllowed()) requestPermissions(new String[]{Manifest.permission.CAMERA, HEADSET_CAMERA}, CAMERA);
        else chooseCamera();
    }
    private void chooseCamera() {
        try {
            String[] ids = camera.ids();
            if (ids.length == 0) { showStatus("No headset cameras exposed. Check Horizon OS and permissions."); return; }
            new AlertDialog.Builder(this).setTitle("Choose headset camera")
                .setItems(ids, (dialog, index) -> camera.open(ids[index])).setNegativeButton("Cancel", null).show();
        } catch (Exception e) { showStatus("Camera unavailable: " + e.getMessage()); }
    }
    private void attachImage(byte[] jpeg, boolean autoSend) {
        if (jpeg.length > 4 * 1024 * 1024) { stopLive(); showStatus("Image too large to attach."); return; }
        int token = liveGeneration;
        evaluate("attach(" + JSONObject.quote(Base64.encodeToString(jpeg, Base64.NO_WRAP)) + "," + autoSend + "," + JSONObject.quote(livePrompt) + ")", result -> {
            if (autoSend && (!live || token != liveGeneration)) return;
            if (autoSend && "staged".equals(result)) { staged = true; showStatus("LIVE · waiting for image upload · Stop to end"); }
            else if (autoSend && ("busy".equals(result) || "draft-present".equals(result))) showStatus("LIVE · skipped frame: conversation busy or draft present");
            else if (autoSend) { stopLive(); showStatus("Live stopped: " + result); }
            else showStatus("attached".equals(result) ? "Photo attached. Review it and press Send in ChatGPT." : "Attachment failed: " + result);
        });
    }
    private void confirmLive() {
        if (live) { stopLive(); showStatus("Live sharing stopped; any staged image remains in the composer."); return; }
        if (!camera.ready() || !isChatPage()) { showStatus("Sign into ChatGPT and open Camera first."); return; }
        new AlertDialog.Builder(this).setTitle("Share live camera snapshots?")
            .setMessage("Send a camera image and your analysis prompt every " + intervalSeconds + " seconds, up to " + frameLimit + " images. This uses your account's image/message allowance. Stop or leaving the app ends sharing. Frames are skipped while ChatGPT is busy or you have a draft.")
            .setPositiveButton("Start sharing", (dialog, which) -> {
                stopLive(); live = true; sentFrames = 0; liveUrl = web.getUrl();
                showStatus("LIVE · camera sharing started · Stop to end");
                handler.post(liveTick); handler.post(sendTick);
            }).setNegativeButton("Cancel", null).show();
    }
    private String windowId() {
        String value = getIntent().getStringExtra("window_id");
        try { return java.util.UUID.fromString(value).toString(); }
        catch (IllegalArgumentException | NullPointerException e) { return null; }
    }
    private SharedPreferences accountOptions() {
        String id = windowId();
        return getSharedPreferences("account-options" + (id == null ? "" : "." + id), MODE_PRIVATE);
    }
    private static boolean officialUrl(String url) {
        Uri uri = Uri.parse(url);
        return "https".equals(uri.getScheme()) && "chatgpt.com".equals(uri.getHost()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    private void workspaceEvent(String event, String url) {
        if (!getIntent().getBooleanExtra("spatial_panel", false)) return;
        Intent intent = new Intent(getPackageName() + ".WORKSPACE_EVENT").setPackage(getPackageName())
            .putExtra("event", event).putExtra("window_id", windowId());
        if (url != null && officialUrl(url)) intent.putExtra("url", url);
        sendBroadcast(intent);
    }
    private void tools() {
        String[] choices = {"Open workspace browser", "New HTML widget", "Ask AI to build a widget", "Create widget from latest AI reply", "Open ChatGPT in Quest Browser"};
        new AlertDialog.Builder(this).setTitle("Assistant tools").setItems(choices, (dialog, which) -> {
            if (which == 0) openBrowser("https://www.wikipedia.org/");
            else if (which == 1) workspaceEvent("new_widget", null);
            else if (which == 2) widgetPrompt();
            else if (which == 3) evaluate("latestHtml()", result -> {
                if ("no-widget-html".equals(result) || "widget-too-large".equals(result) || result.length() == 0) {
                    showStatus("No usable HTML found in the latest AI reply. Ask for one self-contained HTML code block, or paste code into a blank widget."); return;
                }
                if (result.getBytes(StandardCharsets.UTF_8).length > WidgetDocument.MAX_HTML_BYTES) { showStatus("Widget HTML exceeds 128 KB."); return; }
                if (!result.contains("<")) { showStatus("Wait for an HTML reply before creating a widget."); return; }
                Intent intent = new Intent(getPackageName() + ".WORKSPACE_EVENT").setPackage(getPackageName()).putExtra("event", "create_widget").putExtra("window_id", windowId()).putExtra("html", result);
                sendBroadcast(intent);
            });
            else externalBrowser("https://chatgpt.com/");
        }).show();
    }
    private void widgetPrompt() {
        EditText description = new EditText(this); description.setHint("A budget tracker, study timer, dashboard…"); description.setMinLines(2);
        new AlertDialog.Builder(this).setTitle("What should your widget do?").setView(description).setPositiveButton("Create draft", (dialog, which) -> {
            String request = description.getText().toString().trim();
            if (request.isEmpty()) return;
            offerDraft("Build a Metamanning HTML widget for: " + request +
                "\nReturn one self-contained fenced html code block with inline CSS and JavaScript. Use a responsive dark design and large touch targets. No remote resources, fetch, eval, popups or external frameworks. The widget runs in an opaque sandboxed iframe with scripts enabled. Optional local persistence: window.metamanning.loadState() returns an object; window.metamanning.saveState(object) saves up to 32 KB. It has no camera, microphone or ChatGPT-account access. Use DOM APIs safely, preserve existing saved state, and provide readable controls. Do not send anything automatically.");
        }).setNegativeButton("Cancel", null).show();
    }
    private void offerDraft(String prompt) {
        if (live) stopLive();
        evaluate("draft(" + JSONObject.quote(prompt) + ")", result -> {
            if ("ok".equals(result)) { showStatus("Draft inserted. Review it and press Send when ready."); return; }
            new AlertDialog.Builder(this).setTitle("Keep this proposed draft?").setMessage("The message box is busy, unavailable, or already contains text. Your existing draft has been preserved.")
                .setPositiveButton("Copy proposed text", (dialog, which) -> {
                    ((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(android.content.ClipData.newPlainText("Assistant draft", prompt));
                    showStatus("Proposed text copied. Paste it when you are ready.");
                }).setNegativeButton("Cancel", null).show();
        });
    }
    private void options() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout fields = new LinearLayout(this); fields.setOrientation(LinearLayout.VERTICAL); fields.setPadding(24, 12, 24, 12);
        TextView rateLabel = new TextView(this); rateLabel.setText("Live image interval: " + intervalSeconds + " seconds"); fields.addView(rateLabel);
        SeekBar rate = new SeekBar(this); rate.setMax(55); rate.setProgress(intervalSeconds - 5); fields.addView(rate);
        TextView limitLabel = new TextView(this); limitLabel.setText("Images per session (1–100)"); fields.addView(limitLabel);
        EditText limit = new EditText(this); limit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER); limit.setText(Integer.toString(frameLimit)); fields.addView(limit);
        TextView zoomLabel = new TextView(this); zoomLabel.setText("Conversation text size"); fields.addView(zoomLabel);
        SeekBar zoom = new SeekBar(this); zoom.setMax(80); zoom.setProgress(textZoom - 80); fields.addView(zoom);
        TextView promptLabel = new TextView(this); promptLabel.setText("Live camera question"); fields.addView(promptLabel);
        EditText prompt = new EditText(this); prompt.setText(livePrompt); prompt.setMinLines(2); prompt.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(1000)}); fields.addView(prompt);
        rate.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { rateLabel.setText("Live image interval: " + (value + 5) + " seconds"); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        scroll.addView(fields);
        new AlertDialog.Builder(this).setTitle("Conversation options").setView(scroll).setPositiveButton("Save", (dialog, which) -> {
            int requested;
            try { requested = Integer.parseInt(limit.getText().toString()); } catch (NumberFormatException e) { requested = 20; }
            intervalSeconds = rate.getProgress() + 5; frameLimit = Math.max(1, Math.min(100, requested));
            textZoom = zoom.getProgress() + 80; livePrompt = prompt.getText().toString().trim();
            if (livePrompt.isEmpty()) livePrompt = "Describe this camera view briefly.";
            accountOptions().edit().putInt("interval", intervalSeconds).putInt("limit", frameLimit).putInt("zoom", textZoom).putString("prompt", livePrompt).apply();
            web.getSettings().setTextZoom(textZoom); stopLive(); showStatus("Options saved. Live sharing is off until you start it again.");
        }).setNegativeButton("Cancel", null).show();
    }
    private void stopLive() {
        live = false; staged = false; capturePurpose = 0; liveGeneration++;
        handler.removeCallbacks(liveTick); handler.removeCallbacks(sendTick);
        if (isChatPage()) web.evaluateJavascript("window.metamanning && window.metamanning.cancel()", null);
    }
    private void finishPhoto(Uri[] uris) {
        if (photoResult != null) { photoResult.onReceiveValue(uris); photoResult = null; }
    }
    private void addButton(LinearLayout bar, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        ShellStyle.button(this, button, title);
        button.setMinHeight((int)(56 * getResources().getDisplayMetrics().density));
        button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, ShellStyle.dp(this, 48), 1);
        size.setMargins(ShellStyle.dp(this, 3), 0, ShellStyle.dp(this, 3), ShellStyle.dp(this, 6));
        bar.addView(button, size);
    }
    private void openBrowser(String url) {
        if (getIntent().getBooleanExtra("spatial_panel", false)) {
            Intent intent = new Intent(getPackageName() + ".WORKSPACE_EVENT").setPackage(getPackageName()).putExtra("event", "new_browser").putExtra("window_id", windowId()).putExtra("url", url);
            sendBroadcast(intent); showStatus("Browser requested in your workspace."); return;
        }
        externalBrowser(url);
    }
    private void externalBrowser(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (ActivityNotFoundException e) { notice("No browser installed."); }
    }
    private void notice(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onSaveInstanceState(Bundle out) { web.saveState(out); super.onSaveInstanceState(out); }
    @Override protected void onPause() {
        stopLive(); camera.close(); showStatus("Camera sharing stopped while app is inactive.");
        if (pendingMic != null) { pendingMic.deny(); pendingMic = null; }
        web.onPause(); CookieManager.getInstance().flush(); super.onPause();
    }
    @Override protected void onResume() { super.onResume(); if (web != null && !panelHidden) web.onResume(); }
    @Override public void onBackPressed() { if (web.canGoBack()) web.goBack(); else super.onBackPressed(); }
    @Override protected void onDestroy() {
        if (getIntent().getBooleanExtra("spatial_panel", false)) {
            try { unregisterReceiver(workspaceControl); } catch (IllegalArgumentException ignored) {}
            if (isFinishing()) workspaceEvent("closed", null);
        }
        destroyed = true; stopLive(); camera.close(); imageWorker.shutdownNow();
        finishPhoto(null);
        if (pendingMic != null) { pendingMic.deny(); pendingMic = null; }
        web.destroy();
        super.onDestroy();
    }
}
