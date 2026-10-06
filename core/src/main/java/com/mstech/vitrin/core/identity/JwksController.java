package com.mstech.vitrin.core.identity;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
final class JwksController {
    private final SigningKeys keys;

    JwksController(SigningKeys keys) {
        this.keys = keys;
    }

    @GetMapping("/internal/jwks")
    Map<String, Object> jwks() {
        return keys.publicJwkSet();
    }
}
