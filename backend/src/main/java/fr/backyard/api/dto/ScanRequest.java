package fr.backyard.api.dto;

import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Scan d'un passage (E6). {@code scannedAt} n'est pas annoté : son absence est une règle du service
 * d'enregistrement (RG20).
 */
public record ScanRequest(
    @NotBlank String qrToken,
    Instant scannedAt
) {
}
