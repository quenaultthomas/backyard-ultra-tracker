package fr.backyard.service;

import java.util.List;
import java.util.Optional;

/**
 * Résultat de la clôture d'un yard pour une course (RG18).
 *
 * @param raceId            id de la course traitée
 * @param closedYard        yard clôturé, 0 si rien à clôturer
 * @param timedOutRunnerIds coureurs passés DNF par timeout lors de cette clôture
 * @param winnerRunnerId    vainqueur désigné lors de cette clôture, vide sinon
 * @param raceFinished      vrai si la course est passée FINISHED lors de cette clôture
 */
public record YardClosingResult(
    Long raceId,
    int closedYard,
    List<Long> timedOutRunnerIds,
    Optional<Long> winnerRunnerId,
    boolean raceFinished
) {

    public YardClosingResult {
        timedOutRunnerIds = List.copyOf(timedOutRunnerIds);
    }

    static YardClosingResult nothingToClose(Long raceId) {
        return new YardClosingResult(raceId, 0, List.of(), Optional.empty(), false);
    }
}
