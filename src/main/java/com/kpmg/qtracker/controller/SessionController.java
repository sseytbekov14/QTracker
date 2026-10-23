package com.kpmg.qtracker.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The keepalive of js/session.js while someone types: the request itself restarts the 15 minutes of the session.
 * Without a session the chain answers 401 SESSION_EXPIRED before this is reached.
 */
@RestController
public class SessionController {

    @GetMapping("/api/session/keepalive")
    public ResponseEntity<Void> keepalive() {
        return ResponseEntity.noContent().build();
    }
}
