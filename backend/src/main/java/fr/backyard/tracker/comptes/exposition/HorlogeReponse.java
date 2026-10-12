package fr.backyard.tracker.comptes.exposition;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/** État de l'horloge pilotable (profil e2e) : instant courant UTC aux millisecondes et décalage cumulé. */
public record HorlogeReponse(String maintenant, String decalage, long decalageMillisecondes) {

    private static final DateTimeFormatter INSTANT_AUX_MILLISECONDES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    static HorlogeReponse depuis(Instant maintenant, Duration decalage) {
        return new HorlogeReponse(INSTANT_AUX_MILLISECONDES.format(maintenant), decalage.toString(),
                decalage.toMillis());
    }
}
