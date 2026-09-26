package fr.backyard.api.dto;

import fr.backyard.service.SessionView;

import java.time.Instant;

/** Session authentifiée renvoyée par E19 {@code GET /api/scan/me} (RG52 inc. 4). */
public record SessionResponse(
    String username,
    String role,
    Instant serverTime
) {

    public static SessionResponse from(SessionView view) {
        return new SessionResponse(view.username(), view.role(), view.serverTime());
    }
}
