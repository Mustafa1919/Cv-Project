package com.mstech.vitrin.gateway.auth;

import java.io.IOException;
import java.security.interfaces.ECPublicKey;
import java.util.Map;

@FunctionalInterface
public interface JwksClient {
    Map<String, ECPublicKey> fetch() throws IOException;
}
