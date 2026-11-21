package com.finflow.tenant.api;

import com.finflow.security.MissingTenantException;
import com.finflow.tenant.application.SlugAlreadyUsedException;
import com.finflow.tenant.application.TenantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Erreurs métier en Problem Details (RFC 9457). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail missingTenant(MissingTenantException e) {
        return problem(HttpStatus.FORBIDDEN, "Organisation absente", e.getMessage());
    }

    @ExceptionHandler(TenantNotFoundException.class)
    ProblemDetail notFound(TenantNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Organisation introuvable", e.getMessage());
    }

    @ExceptionHandler(SlugAlreadyUsedException.class)
    ProblemDetail slugConflict(SlugAlreadyUsedException e) {
        return problem(HttpStatus.CONFLICT, "Identifiant court déjà utilisé", e.getMessage());
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
