package com.mstech.vitrin.core.identity;

import java.time.Instant;

record IssuedToken(String value, Instant expiresAt) {}
