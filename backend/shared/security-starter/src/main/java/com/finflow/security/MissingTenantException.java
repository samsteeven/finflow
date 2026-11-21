package com.finflow.security;

/** L'appelant est authentifié mais n'est rattaché à aucune organisation. */
public class MissingTenantException extends RuntimeException {

    public MissingTenantException() {
        super("Aucune organisation n'est associée à cet utilisateur");
    }
}
