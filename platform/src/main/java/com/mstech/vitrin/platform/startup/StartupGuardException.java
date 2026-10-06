package com.mstech.vitrin.platform.startup;

/** Thrown during startup when the environment must not be served from. */
public class StartupGuardException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public StartupGuardException(String message) {
        super(message);
    }
}
