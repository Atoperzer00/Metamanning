package com.metamanning.questlite;

import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import org.json.JSONTokener;

final class WidgetStatePolicy {
    static boolean valid(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > 32768) return false;
        int depth = 0; boolean string = false, escape = false;
        for (char c : value.toCharArray()) {
            if (string) { if (escape) escape = false; else if (c == '\\') escape = true; else if (c == '"') string = false; }
            else if (c == '"') string = true;
            else if (c == '{' || c == '[') { if (++depth > 32) return false; }
            else if (c == '}' || c == ']') { if (--depth < 0) return false; }
        }
        if (depth != 0 || string) return false;
        try { JSONTokener tokens = new JSONTokener(value); return tokens.nextValue() instanceof JSONObject && tokens.nextClean() == 0; }
        catch (Exception e) { return false; }
    }
}
