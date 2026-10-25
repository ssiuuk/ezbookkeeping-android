package com.ssiuuk.ezbookkeeping.data;

/** A failed call to the server, with the server's own message when it sent one. */
public final class ApiException extends Exception {
    public final int httpStatus;
    public final int errorCode;
    /** No answer came: no network, or the server is down or out of reach. */
    public final boolean unreachable;

    public ApiException(int httpStatus, int errorCode, String message) {
        this(httpStatus, errorCode, message, false);
    }

    private ApiException(int httpStatus, int errorCode, String message, boolean unreachable) {
        super(message);
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.unreachable = unreachable;
    }

    public static ApiException unreachable(String message) {
        return new ApiException(0, 0, message, true);
    }

    public boolean isUnauthorized() {
        return httpStatus == 401;
    }

    public boolean isNotFound() {
        return httpStatus == 404;
    }
}
