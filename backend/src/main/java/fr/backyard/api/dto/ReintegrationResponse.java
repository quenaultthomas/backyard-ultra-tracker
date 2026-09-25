package fr.backyard.api.dto;

import java.util.List;

/** Coureur réintégré et passages recréés, par yard croissant (RG22). */
public record ReintegrationResponse(
    AdminRunnerResponse runner,
    List<PassageResponse> recreatedPassages
) {
}
