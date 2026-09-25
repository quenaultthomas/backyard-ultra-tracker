package fr.backyard.service;

import fr.backyard.domain.Race;

import java.time.Instant;
import java.util.List;

/**
 * Tableau de bord d'une course (RG24 inc. 3), construit dans la transaction de lecture.
 *
 * @param serverTime        instant de l'horloge serveur au moment du calcul
 * @param currentYard       0 si la course n'est pas RUNNING
 * @param currentYardEndsAt null si currentYard vaut 0
 * @param runners           triés par dossard croissant
 */
public record RaceBoardView(
    Race race,
    Instant serverTime,
    int currentYard,
    Instant currentYardEndsAt,
    List<RunnerBoardEntry> runners
) {

    public RaceBoardView {
        runners = List.copyOf(runners);
    }
}
