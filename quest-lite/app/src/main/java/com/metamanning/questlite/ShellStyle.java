package com.metamanning.questlite;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Platform-only dark chrome: no UI framework, icon font, or image download at runtime. */
final class ShellStyle {
    static int dp(Context context, int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    static GradientDrawable panel(Context context, String fill, String border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(Color.parseColor(fill)); result.setCornerRadius(dp(context, radius));
        result.setStroke(dp(context, 1), Color.parseColor(border)); return result;
    }
    static void header(Context context, LinearLayout root) {
        root.setBackgroundColor(Color.parseColor("#0B1019"));
        root.setPadding(dp(context, 12), dp(context, 6), dp(context, 12), dp(context, 8));
        LinearLayout header = new LinearLayout(context); header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(dp(context, 4), dp(context, 4), dp(context, 4), dp(context, 12));
        ImageView mark = new ImageView(context); mark.setImageResource(R.drawable.ic_metamanning);
        mark.setContentDescription("Metamanning"); header.addView(mark, new LinearLayout.LayoutParams(dp(context, 38), dp(context, 38)));
        LinearLayout titles = new LinearLayout(context); titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(context, 12), 0, 0, 0);
        TextView title = new TextView(context); title.setText(R.string.app_brand); title.setTextSize(21);
        title.setTextColor(Color.parseColor("#F2F6FC")); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        TextView subtitle = new TextView(context); subtitle.setText(R.string.app_subtitle); subtitle.setTextSize(11);
        subtitle.setTextColor(Color.parseColor("#95A6BD")); titles.addView(title); titles.addView(subtitle);
        header.addView(titles); root.addView(header);
    }
    static void button(Context context, Button button, String title) {
        boolean live = "Live".equals(title), stop = "Stop".equals(title);
        button.setAllCaps(false); button.setTextSize(13); button.setElevation(0);
        button.setTextColor(Color.parseColor(live ? "#91F4D4" : stop ? "#FFB3BB" : "#DEE7F5"));
        button.setBackground(panel(context, live ? "#153A32" : stop ? "#38212B" : "#182230",
            live ? "#2F7961" : stop ? "#6A3B49" : "#29394C", 13));
        button.setPadding(dp(context, 6), dp(context, 4), dp(context, 6), dp(context, 4));
    }
    static void status(Context context, TextView status, String message) {
        boolean sharing = message.startsWith("LIVE");
        status.setText(message); status.setTextSize(12);
        status.setTextColor(Color.parseColor(sharing ? "#96F5D5" : "#A6B7CD"));
        status.setBackground(panel(context, sharing ? "#12352E" : "#101925", sharing ? "#2F7961" : "#233246", 12));
    }
}
