package com.mstech.vitrin.gateway.status;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class StatusController {
    private final StatusService service;

    public StatusController(StatusService service) {
        this.service = service;
    }

    @GetMapping(value = "/v1/public/status", produces = MediaType.APPLICATION_JSON_VALUE)
    public StatusSnapshot current() {
        return service.current();
    }
}
