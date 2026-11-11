package com.metamanning.questlite;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.graphics.Color;
import android.webkit.WebView;
import android.widget.*;
import java.util.UUID;

/** Common private panel lifecycle. It exposes no native interface to remote webpages. */
@android.annotation.TargetApi(34)
public abstract class WorkspacePanelActivity extends Activity {
    protected LinearLayout layout;
    protected TextView status;
    protected WebView web;
    protected String windowId;
    protected boolean hidden, destroyed, resumed;
    private static boolean profileReady;
    private final BroadcastReceiver controls = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!windowId.equals(intent.getStringExtra("window_id"))) return;
            String command = intent.getStringExtra("command");
            if ("close".equals(command)) finish();
            else if ("pause".equals(command)) { hidden = true; if (web != null) web.onPause(); visibilityChanged(false); }
            else if ("resume".equals(command)) { hidden = false; if (web != null) web.onResume(); visibilityChanged(true); }
            else onWorkspaceControl(command, intent);
        }
    };
    protected void initialize(String profile) {
        try { windowId = UUID.fromString(getIntent().getStringExtra("window_id")).toString(); }
        catch (Exception e) { finish(); return; }
        if (!profileReady) { WebView.setDataDirectorySuffix(profile); profileReady = true; }
        hidden = getIntent().getBooleanExtra("initial_hidden", false);
        registerReceiver(controls, new IntentFilter(getPackageName() + ".WORKSPACE_CONTROL"), Context.RECEIVER_NOT_EXPORTED);
        layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); ShellStyle.header(this, layout);
        status = new TextView(this); status.setTextColor(Color.parseColor("#A6B7CD")); status.setTextSize(12); status.setPadding(12, 8, 12, 8);
        setContentView(layout);
    }
    protected LinearLayout toolbar() { LinearLayout row = new LinearLayout(this); layout.addView(row); return row; }
    protected void button(LinearLayout bar, String title, Runnable action) {
        Button button = new Button(this); button.setText(title); ShellStyle.button(this, button, title); button.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams dimensions = new LinearLayout.LayoutParams(0, ShellStyle.dp(this, 48), 1);
        dimensions.setMargins(3, 2, 3, 5); bar.addView(button, dimensions);
    }
    protected void attachWeb() { layout.addView(status); layout.addView(web, new LinearLayout.LayoutParams(-1, 0, 1)); }
    protected void event(String name, Bundle values) {
        Intent intent = new Intent(getPackageName() + ".WORKSPACE_EVENT").setPackage(getPackageName()).putExtra("event", name).putExtra("window_id", windowId);
        if (values != null) intent.putExtras(values); sendBroadcast(intent);
    }
    protected void manager() { event("manager", null); }
    protected void message(String text) { if (!destroyed) status.setText(text); }
    protected void visibilityChanged(boolean visible) {}
    protected void onWorkspaceControl(String command, Intent intent) {}
    @Override protected void onResume() { super.onResume(); resumed = true; if (web != null && !hidden) { web.onResume(); visibilityChanged(true); } }
    @Override protected void onPause() { resumed = false; if (web != null) { web.onPause(); visibilityChanged(false); } super.onPause(); }
    @Override protected void onDestroy() {
        destroyed = true;
        if (windowId != null) { try { unregisterReceiver(controls); } catch (IllegalArgumentException ignored) {} if (isFinishing()) event("closed", null); }
        if (web != null) { web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
