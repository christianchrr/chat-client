package com.localai.apihost;

public final class WebContextFetchException extends RuntimeException {
    private final String errorCode;

    public WebContextFetchException(String errorCode) {
        super(errorCode);
        this.errorCode = errorCode;
    }

    public WebContextFetchException(String errorCode, Throwable cause) {
        super(errorCode, cause);
        this.errorCode = errorCode;
    }

    public String errorCode() {
        return errorCode;
    }
}
