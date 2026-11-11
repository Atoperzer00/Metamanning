package com.metamanning.questlite;

import java.net.URI;

public final class WebAddress {
    private WebAddress() {}
    public static boolean allowedHttps(String value) {
        if (value == null) return false;
        try {
            URI uri = new URI(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty()
                && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
        } catch (Exception e) { return false; }
    }
}
