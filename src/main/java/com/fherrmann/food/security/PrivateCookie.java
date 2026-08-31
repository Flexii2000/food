package com.fherrmann.food.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The shared "private mode" cookie of fherrmann.com.
 *
 * <p>This app neither issues nor renews it: it is set once per device via
 * {@code https://fherrmann.com/setup?token=<secret>} on {@code Domain=.fherrmann.com},
 * which is exactly why it also arrives here on {@code food.fherrmann.com}. The token
 * itself lives in {@code /etc/nginx/conf.d/private-mode.conf} and is handed to this
 * app through the {@code FH_PRIVATE_TOKEN} environment variable.
 */
final class PrivateCookie {

    static final String NAME = "fh_private";

    private PrivateCookie() {
    }

    static boolean matches(String supplied, String expected) {
        if (supplied == null || expected == null) {
            return false;
        }
        return MessageDigest.isEqual(
                supplied.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8));
    }
}
