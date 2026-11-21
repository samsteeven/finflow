package com.finflow.payment.api;

import com.finflow.payment.application.IdempotencyKeyReusedException;
import com.finflow.payment.application.PaymentNotFoundException;
import com.finflow.payment.domain.IllegalTransitionException;
import com.finflow.payment.domain.InvalidPaymentException;
import com.finflow.security.MissingTenantException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Erreurs métier en Problem Details (RFC 9457). */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail missingTenant(MissingTenantException e) {
        return problem(HttpStatus.FORBIDDEN, "Organisation absente", e.getMessage());
    }

    @ExceptionHandler(PaymentNotFoundException.class)
    ProblemDetail notFound(PaymentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Paiement introuvable", e.getMessage());
    }

    @ExceptionHandler(InvalidPaymentException.class)
    ProblemDetail invalid(InvalidPaymentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Paiement invalide", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Clé d'idempotence déjà utilisée", e.getMessage());
    }

    @ExceptionHandler(IllegalTransitionException.class)
    ProblemDetail illegalTransition(IllegalTransitionException e) {
        return problem(HttpStatus.CONFLICT, "Opération impossible dans l'état du paiement", e.getMessage());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Paramètres invalides", e.getMessage());
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Paramètres invalides",
                e.getMessage()));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
