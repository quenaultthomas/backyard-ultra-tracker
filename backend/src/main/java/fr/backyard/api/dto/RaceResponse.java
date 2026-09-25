package fr.backyard.api.dto;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;

import java.time.Instant;
import java.time.LocalDate;

/** Course exposée en public et en admin : aucune donnée secrète. */
public record RaceResponse(
    Long id,
    String name,
    LocalDate raceDate,
    RaceStatus status,
    Instant startedAt,
    int loopDistance,
    int loopDuration,
    int loopElevation,
    boolean registrationOpen
) {

    public static RaceResponse from(Race race) {
        return new RaceResponse(race.getId(), race.getName(), race.getRaceDate(), race.getStatus(),
            race.getStartedAt(), race.getLoopDistance(), race.getLoopDuration(), race.getLoopElevation(),
            race.isRegistrationOpen());
    }
}
