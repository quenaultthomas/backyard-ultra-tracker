package fr.backyard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Inscription publique d'un coureur (E3). */
public record RegistrationRequest(
    @NotBlank @Size(max = 255) String name
) {
}
