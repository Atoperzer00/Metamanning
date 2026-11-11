package com.metamanning.questlite;

import java.nio.charset.StandardCharsets;

/** Builds the same sandbox document used on Android and in Chromium integration tests. */
public final class WidgetDocument {
    public static final int MAX_HTML_BYTES = 128 * 1024;
    private WidgetDocument() {}
    public static String render(String html, boolean network) {
        if (html == null || html.getBytes(StandardCharsets.UTF_8).length > MAX_HTML_BYTES)
            throw new IllegalArgumentException("Widget HTML exceeds 128 KB");
        String remote = network ? " https:" : "";
        String csp = "default-src 'none'; script-src 'unsafe-inline'" + remote + "; style-src 'unsafe-inline'" + remote
            + "; img-src data: blob:" + remote + "; font-src data:" + remote + "; connect-src " + (network ? "https:" : "'none'")
            + "; frame-src 'self'; object-src 'none'; base-uri 'none'; form-action 'none'";
        String sdk = "<script>(()=>{let memory={};window.metamanning=Object.freeze({loadState:()=>{try{return window.MetamanningState?JSON.parse(MetamanningState.loadState()):memory}catch(e){return {}}},saveState:value=>{const data=JSON.stringify(value);if(window.MetamanningState){if(!MetamanningState.saveState(data))throw new Error('State must be an object under 32 KB')}else{memory=value}return value}})})();</script>";
        String content = "<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><style>html{color-scheme:dark}body{margin:16px;font:16px system-ui;background:#101925;color:#e6edf7}button,input,textarea,select{font:inherit}button{cursor:pointer}</style>" + sdk + html;
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta http-equiv=\"Content-Security-Policy\" content=\"" + attribute(csp)
            + "\"><style>html,body{margin:0;width:100%;height:100%;background:#101925}iframe{border:0;width:100%;height:100%;display:block}</style></head><body><iframe title=\"HTML widget\" sandbox=\"allow-scripts allow-forms\" srcdoc=\"" + attribute(content) + "\"></iframe></body></html>";
    }
    private static String attribute(String value) {
        return value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
