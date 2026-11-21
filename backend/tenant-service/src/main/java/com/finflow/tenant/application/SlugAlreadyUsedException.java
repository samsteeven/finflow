package com.finflow.tenant.application;

public class SlugAlreadyUsedException extends RuntimeException {

    public SlugAlreadyUsedException(String slug) {
        super("L'identifiant court « " + slug + " » est déjà utilisé");
    }
}
