package com.gs.ais.security;

public record AuthPrincipal(AuthRole role, String subject) {
}
