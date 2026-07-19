package com.gs.ais.security;

public enum AuthRole {
    USER,
    ADMIN;

    public boolean satisfies(AuthRole required) {
        if (required == null || required == USER) {
            return true;
        }
        return this == ADMIN;
    }
}
