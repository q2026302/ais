package com.gs.ais.security;

/**
 * Validation helpers for the client-produced password material stored by BCrypt.
 */
public final class PasswordDigests {

    private PasswordDigests() {
    }

    public static boolean isMd5Hex(String value) {
        if (value == null || value.length() != 32) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!(character >= '0' && character <= '9')
                    && !(character >= 'a' && character <= 'f')
                    && !(character >= 'A' && character <= 'F')) {
                return false;
            }
        }
        return true;
    }
}
