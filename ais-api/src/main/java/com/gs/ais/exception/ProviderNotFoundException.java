package com.gs.ais.exception;

public class ProviderNotFoundException extends RuntimeException {

    public ProviderNotFoundException(String message) {
        super(message);
    }

    public ProviderNotFoundException(Long id) {
        super("Provider not found with id: " + id);
    }
}
