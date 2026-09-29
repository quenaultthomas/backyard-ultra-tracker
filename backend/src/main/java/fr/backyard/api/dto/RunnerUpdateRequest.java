package fr.backyard.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Modification d'un coureur par l'admin (E15) : seul le dossard (RG18 inc. 5). Les autres propriétés du JSON,
 * dont {@code name}, sont ignorées.
 */
public record RunnerUpdateRequest(
    @NotNull @Positive Integer bib
) {
}
