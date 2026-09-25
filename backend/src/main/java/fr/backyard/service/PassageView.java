package fr.backyard.service;

import fr.backyard.domain.PassageSource;

import java.time.Instant;
import java.util.OptionalLong;

/**
 * Vue de lecture d'un passage (RG26 inc. 3) : temps de boucle et badge "corrigé" dérivés.
 *
 * @param scannedAt      null pour un passage recréé
 * @param loopTimeMillis vide pour un passage MANUAL
 */
public record PassageView(
    int yardNumber,
    PassageSource source,
    Instant scannedAt,
    OptionalLong loopTimeMillis,
    boolean corrected
) {
}
