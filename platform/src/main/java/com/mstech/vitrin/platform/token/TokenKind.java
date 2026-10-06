package com.mstech.vitrin.platform.token;

public enum TokenKind {
    ACCESS("at+jwt"),
    REFRESH("rt+jwt");

    private final String headerType;

    TokenKind(String headerType) {
        this.headerType = headerType;
    }

    public String headerType() {
        return headerType;
    }
}
