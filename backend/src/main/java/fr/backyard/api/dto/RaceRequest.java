package fr.backyard.api.dto;

import fr.backyard.service.RaceCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/** Création ou remplacement complet d'une course (E7, E10). Contraintes de format uniquement (RG8). */
public record RaceRequest(
    @NotBlank @Size(max = 255) String name,
    @NotNull LocalDate raceDate,
    @NotNull @Positive Integer loopDistance,
    @NotNull @Positive Integer loopDuration,
    @NotNull @PositiveOrZero Integer loopElevation
) {

    public RaceCommand toCommand() {
        return new RaceCommand(name, raceDate, loopDistance, loopDuration, loopElevation);
    }
}
