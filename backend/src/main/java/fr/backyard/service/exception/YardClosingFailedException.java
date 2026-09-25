package fr.backyard.service.exception;

import java.util.List;
import java.util.Map;

/**
 * Échec de la clôture d'au moins une course lors d'un passage périodique (RG17).
 * Levée après le traitement de toutes les courses : cite l'id de chaque course en échec
 * et porte les exceptions d'origine : la première en cause, les suivantes en exceptions supprimées.
 */
public class YardClosingFailedException extends RuntimeException {

    private final List<Long> failedRaceIds;

    public YardClosingFailedException(Map<Long, RuntimeException> failuresByRaceId) {
        super(buildMessage(failuresByRaceId), failuresByRaceId.values().stream().findFirst().orElse(null));
        this.failedRaceIds = List.copyOf(failuresByRaceId.keySet());
        failuresByRaceId.values().stream().skip(1).forEach(this::addSuppressed);
    }

    public List<Long> getFailedRaceIds() {
        return failedRaceIds;
    }

    private static String buildMessage(Map<Long, RuntimeException> failuresByRaceId) {
        StringBuilder message = new StringBuilder("Échec de la clôture des yards pour ")
            .append(failuresByRaceId.size()).append(" course(s) :");
        failuresByRaceId.forEach((raceId, failure) -> message
            .append(" [course ").append(raceId).append(" : ").append(failure.getMessage()).append(']'));
        return message.toString();
    }
}
