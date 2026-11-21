package com.finflow.account.api;

import com.finflow.account.application.AccountNotFoundException;
import com.finflow.account.domain.IllegalAccountTransitionException;
import com.finflow.account.domain.InvalidLimitsException;
import com.finflow.security.MissingTenantException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
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

    @ExceptionHandler(AccountNotFoundException.class)
    ProblemDetail notFound(AccountNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Compte introuvable", e.getMessage());
    }

    @ExceptionHandler(IllegalAccountTransitionException.class)
    ProblemDetail illegalTransition(IllegalAccountTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Opération impossible dans l'état du compte", e.getMessage());
    }

    @ExceptionHandler(InvalidLimitsException.class)
    ProblemDetail invalidLimits(InvalidLimitsException e) {
        return problem(HttpStatus.BAD_REQUEST, "Limites invalides", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Paramètres invalides", e.getMessage());
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException e) {
        return problem(HttpStatus.CONFLICT, "Modification concurrente",
                "Le compte a été modifié entre-temps ; relisez-le avant de réessayer");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
