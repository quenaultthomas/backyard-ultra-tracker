package fr.backyard.service;

import java.time.LocalDate;

/**
 * Paramètres d'une course saisis par l'admin (création et remplacement complet, RG9 et RG11 inc. 3).
 */
public record RaceCommand(
    String name,
    LocalDate raceDate,
    int loopDistance,
    int loopDuration,
    int loopElevation
) {
}
