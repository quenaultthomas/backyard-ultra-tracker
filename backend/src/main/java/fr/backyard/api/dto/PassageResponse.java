package fr.backyard.api.dto;

import fr.backyard.domain.PassageSource;
import fr.backyard.service.PassageView;

import java.time.Instant;

/** Passage d'un coureur avec ses valeurs dérivées (null si non définies). */
public record PassageResponse(
    int yardNumber,
    PassageSource source,
    Instant scannedAt,
    Long loopTimeMillis,
    boolean corrected
) {

    public static PassageResponse from(PassageView view) {
        return new PassageResponse(view.yardNumber(), view.source(), view.scannedAt(),
            NullableValues.of(view.loopTimeMillis()), view.corrected());
    }
}
