package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.application.EnregistrerPassage;
import fr.backyard.tracker.courses.domaine.OriginePassage;
import fr.backyard.tracker.courses.domaine.Passage;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Passage créé ou rejoué, restitué au bénévole qui a scanné. {@code scanneLe} est un instant UTC toujours aux
 * millisecondes. Jamais de jeton ni d'identifiant de Compte.
 */
public record PassageReponse(UUID passageId, UUID inscriptionId, int dossard, String pseudo, int numeroBoucle,
                             String scanneLe, OriginePassage origine) {

    private static final DateTimeFormatter INSTANT_AUX_MILLISECONDES =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    /** Identifiant uniquement : ni pseudo ni dossard dans le journal. */
    @Override
    public String toString() {
        return "PassageReponse[id=" + passageId + "]";
    }

    static PassageReponse depuis(EnregistrerPassage.Resultat resultat) {
        Passage passage = resultat.passage();
        return new PassageReponse(passage.id(), passage.inscriptionId(), resultat.dossard(), resultat.pseudo(),
                passage.numeroBoucle(), passage.scanneLe().map(INSTANT_AUX_MILLISECONDES::format).orElse(null),
                passage.origine());
    }
}
