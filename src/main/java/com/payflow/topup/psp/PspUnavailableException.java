package com.payflow.topup.psp;

/** Transient provider failure: the charge outcome is unknown and the call may be retried. */
public class PspUnavailableException extends RuntimeException {

    public PspUnavailableException(String message) {
        super(message);
    }
}
