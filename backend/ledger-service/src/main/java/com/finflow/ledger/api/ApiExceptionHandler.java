package com.finflow.ledger.api;

import com.finflow.ledger.application.AccountNotActiveException;
import com.finflow.ledger.application.AccountServiceUnavailableException;
import com.finflow.ledger.application.IdempotencyKeyReusedException;
import com.finflow.ledger.application.InsufficientFundsException;
import com.finflow.ledger.application.LedgerAccountNotFoundException;
import com.finflow.ledger.application.NotReversibleException;
import com.finflow.ledger.application.TransactionNotFoundException;
import com.finflow.ledger.domain.InvalidPostingException;
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

/**
 * Erreurs en Problem Details (RFC 9457). La propriété {@code code} est le contrat avec Payment :
 * un refus métier (FONDS_INSUFFISANTS) ne s'y confond pas avec une panne.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(MissingTenantException.class)
    ProblemDetail missingTenant(MissingTenantException e) {
        return problem(HttpStatus.FORBIDDEN, "Organisation absente", e.getMessage(), "ORGANISATION_ABSENTE");
    }

    @ExceptionHandler(InsufficientFundsException.class)
    ProblemDetail insufficientFunds(InsufficientFundsException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Fonds insuffisants", e.getMessage(),
                InsufficientFundsException.CODE);
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail keyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "Clé d'idempotence déjà utilisée", e.getMessage(),
                IdempotencyKeyReusedException.CODE);
    }

    @ExceptionHandler(NotReversibleException.class)
    ProblemDetail notReversible(NotReversibleException e) {
        return problem(HttpStatus.CONFLICT, "Contrepassation impossible", e.getMessage(),
                NotReversibleException.CODE);
    }

    @ExceptionHandler({LedgerAccountNotFoundException.class, TransactionNotFoundException.class})
    ProblemDetail notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, "Introuvable", e.getMessage(), "INTROUVABLE");
    }

    @ExceptionHandler(AccountNotActiveException.class)
    ProblemDetail notActive(AccountNotActiveException e) {
        return problem(HttpStatus.CONFLICT, "Compte inactif", e.getMessage(), "COMPTE_INACTIF");
    }

    @ExceptionHandler(AccountServiceUnavailableException.class)
    ProblemDetail accountsDown(AccountServiceUnavailableException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service des comptes indisponible", e.getMessage(),
                "COMPTES_INDISPONIBLES");
    }

    @ExceptionHandler(InvalidPostingException.class)
    ProblemDetail invalid(InvalidPostingException e) {
        return problem(HttpStatus.BAD_REQUEST, "Écriture invalide", e.getMessage(), "ECRITURE_INVALIDE");
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ProblemDetail constraintViolation(ConstraintViolationException e) {
        return problem(HttpStatus.BAD_REQUEST, "Paramètres invalides", e.getMessage(), "PARAMETRES_INVALIDES");
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return ResponseEntity.badRequest().body(problem(HttpStatus.BAD_REQUEST, "Paramètres invalides",
                e.getMessage(), "PARAMETRES_INVALIDES"));
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail, String code) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setProperty("code", code);
        return problem;
    }
}
