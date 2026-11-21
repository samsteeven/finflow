package com.finflow.tenant.application;

import java.util.UUID;

public class TenantNotFoundException extends RuntimeException {

    public TenantNotFoundException(UUID id) {
        super("Organisation introuvable : " + id);
    }
}
