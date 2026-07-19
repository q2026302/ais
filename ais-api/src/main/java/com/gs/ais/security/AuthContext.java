package com.gs.ais.security;

public final class AuthContext {

    private static final ThreadLocal<AuthPrincipal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthPrincipal principal) {
        HOLDER.set(principal);
    }

    public static AuthPrincipal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    public static boolean isAdmin() {
        AuthPrincipal principal = HOLDER.get();
        return principal != null && principal.role() == AuthRole.ADMIN;
    }
}
