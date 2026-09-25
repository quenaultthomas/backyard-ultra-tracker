package fr.backyard.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Modification d'un coureur par l'admin (E15). Les autres propriétés du JSON sont ignorées. */
public record RunnerUpdateRequest(
    @NotNull @Positive Integer bib,
    @NotBlank @Size(max = 255) String name
) {
}
