package fr.backyard.tracker.courses.domaine;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Enregistrement brut et immuable du franchissement de la ligne par une Inscription pour une Boucle. Un Passage SCAN
 * porte l'instant du scan (à la milliseconde) ; un Passage CORRECTION (réintégration) n'en a pas. Tout ce qui en
 * découle (boucles terminées, distance, allure, classement) est calculé, jamais stocké.
 */
public final class Passage {

    private final UUID id;
    private final UUID inscriptionId;
    private final int numeroBoucle;
    private final Instant scanneLe;
    private final OriginePassage origine;

    private Passage(UUID id, UUID inscriptionId, int numeroBoucle, Instant scanneLe, OriginePassage origine) {
        if (numeroBoucle < 1) {
            throw new IllegalArgumentException("Le numéro de boucle d'un passage commence à 1");
        }
        if ((origine == OriginePassage.SCAN) != (scanneLe != null)) {
            throw new IllegalArgumentException("Seul un passage scanné a un instant de scan");
        }
        this.id = Objects.requireNonNull(id);
        this.inscriptionId = Objects.requireNonNull(inscriptionId);
        this.numeroBoucle = numeroBoucle;
        this.scanneLe = scanneLe;
        this.origine = Objects.requireNonNull(origine);
    }

    /**
     * Passage scanné de l'Inscription sur la Boucle donnée.
     *
     * @param id identifiant du scan, tiré par le client et conservé pour ses nouvelles tentatives
     * @param scanneLe instant du scan, tronqué à la milliseconde
     */
    public static Passage scanner(UUID id, Inscription inscription, int numeroBoucle, Instant scanneLe) {
        Objects.requireNonNull(scanneLe, "L'instant du scan est obligatoire");
        return new Passage(id, inscription.id(), numeroBoucle, scanneLe.truncatedTo(ChronoUnit.MILLIS),
                OriginePassage.SCAN);
    }

    /** Passage recréé par correction (réintégration) : sans instant de scan. */
    public static Passage corriger(UUID id, Inscription inscription, int numeroBoucle) {
        return new Passage(id, inscription.id(), numeroBoucle, null, OriginePassage.CORRECTION);
    }

    /** Reconstitue un Passage déjà enregistré. */
    public static Passage reconstituer(UUID id, UUID inscriptionId, int numeroBoucle, Instant scanneLe,
                                       OriginePassage origine) {
        return new Passage(id, inscriptionId, numeroBoucle, scanneLe, origine);
    }

    public UUID id() {
        return id;
    }

    public UUID inscriptionId() {
        return inscriptionId;
    }

    public int numeroBoucle() {
        return numeroBoucle;
    }

    /** Instant du scan ; vide pour un Passage CORRECTION. */
    public Optional<Instant> scanneLe() {
        return Optional.ofNullable(scanneLe);
    }

    public OriginePassage origine() {
        return origine;
    }

    @Override
    public String toString() {
        return "Passage[id=" + id + ", numeroBoucle=" + numeroBoucle + ", origine=" + origine + "]";
    }
}
