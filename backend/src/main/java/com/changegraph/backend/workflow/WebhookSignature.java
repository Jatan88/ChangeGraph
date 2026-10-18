package com.changegraph.backend.workflow;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class WebhookSignature {

    private WebhookSignature() {
    }

    public static boolean matches(String secret, byte[] body, String header) {
        if (secret == null || secret.isBlank() || body == null || header == null || !header.startsWith("sha256=")) {
            return false;
        }
        byte[] actual = decodeHex(header.substring("sha256=".length()));
        if (actual == null) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal(body);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception ex) {
            return false;
        }
    }

    private static byte[] decodeHex(String hex) {
        if (hex.length() % 2 != 0 || hex.isEmpty()) {
            return null;
        }
        byte[] bytes = new byte[hex.length() / 2];
        for (int index = 0; index < bytes.length; index++) {
            int high = Character.digit(hex.charAt(index * 2), 16);
            int low = Character.digit(hex.charAt(index * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[index] = (byte) ((high << 4) + low);
        }
        return bytes;
    }
}
