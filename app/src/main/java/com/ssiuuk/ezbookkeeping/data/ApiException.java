package com.ssiuuk.ezbookkeeping.data;

/** A failed call to the server, with the server's own message when it sent one. */
public final class ApiException extends Exception {
    public final int httpStatus;
    public final int errorCode;

    public ApiException(int httpStatus, int errorCode, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
    }

    public boolean isUnauthorized() {
        return httpStatus == 401;
    }

    public boolean isNotFound() {
        return httpStatus == 404;
    }
}
