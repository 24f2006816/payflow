package com.payflow.domain;

/**
 * Base class for business-rule violations. Carries a stable, machine-readable {@code code}
 * that the API layer maps to an HTTP status and a problem+json "type".
 * Framework-free on purpose: the domain package must not depend on Spring.
 */
public class DomainException extends RuntimeException {

    private final String code;

    public DomainException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }

    public static final class InsufficientFunds extends DomainException {
        public InsufficientFunds(String message) {
            super("insufficient_funds", message);
        }
    }

    public static final class InvalidStateTransition extends DomainException {
        public InvalidStateTransition(String message) {
            super("invalid_state_transition", message);
        }
    }

    public static final class UnbalancedEntry extends DomainException {
        public UnbalancedEntry(String message) {
            super("unbalanced_journal_entry", message);
        }
    }

    public static final class InvalidAmount extends DomainException {
        public InvalidAmount(String message) {
            super("invalid_amount", message);
        }
    }

    public static final class CurrencyMismatch extends DomainException {
        public CurrencyMismatch(String message) {
            super("currency_mismatch", message);
        }
    }
}
