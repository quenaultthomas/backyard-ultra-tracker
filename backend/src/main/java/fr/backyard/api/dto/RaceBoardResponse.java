package fr.backyard.api.dto;

import fr.backyard.service.RaceBoardView;

import java.time.Instant;
import java.util.List;

/** Tableau de bord public d'une course (RG24). */
public record RaceBoardResponse(
    RaceResponse race,
    Instant serverTime,
    int currentYard,
    Instant currentYardEndsAt,
    List<RunnerBoardEntryResponse> runners
) {

    public static RaceBoardResponse from(RaceBoardView view) {
        return new RaceBoardResponse(RaceResponse.from(view.race()), view.serverTime(), view.currentYard(),
            view.currentYardEndsAt(), view.runners().stream().map(RunnerBoardEntryResponse::from).toList());
    }
}
