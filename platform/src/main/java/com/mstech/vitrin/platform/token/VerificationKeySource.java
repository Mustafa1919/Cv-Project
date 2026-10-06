package com.mstech.vitrin.platform.token;

import java.security.interfaces.ECPublicKey;
import org.jspecify.annotations.Nullable;

@FunctionalInterface
public interface VerificationKeySource {
    @Nullable ECPublicKey find(String keyId);
}
