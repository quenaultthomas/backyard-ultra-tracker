package fr.backyard.tracker.courses.domaine;

import java.time.Duration;

/**
 * Anti-doublon : le scan de la Boucle visée suit de moins que le délai minimal le Passage de la Boucle précédente
 * de la même Inscription. Ni jeton ni pseudo dans le message.
 */
public class PassageTropRapprocheException extends RefusPassageCoureurException {

    private static final long serialVersionUID = 1L;

    private final int numeroBoucle;
    private final Duration delaiMinimal;
    private final long secondesRestantes;

    public PassageTropRapprocheException(int numeroBoucle, Duration delaiMinimal, long secondesRestantes) {
        this(numeroBoucle, delaiMinimal, secondesRestantes, null);
    }

    public PassageTropRapprocheException(int numeroBoucle, Duration delaiMinimal, long secondesRestantes,
                                         CoureurScanne coureur) {
        super("Passage trop rapproché du précédent pour la boucle " + numeroBoucle, coureur);
        this.numeroBoucle = numeroBoucle;
        this.delaiMinimal = delaiMinimal;
        this.secondesRestantes = secondesRestantes;
    }

    /** Boucle visée par le scan. */
    public int numeroBoucle() {
        return numeroBoucle;
    }

    /** Délai minimal entre deux Passages de la Course. */
    public Duration delaiMinimal() {
        return delaiMinimal;
    }

    /** Secondes à attendre avant un nouveau Passage, arrondies au-dessus, au moins 1. */
    public long secondesRestantes() {
        return secondesRestantes;
    }

    @Override
    public PassageTropRapprocheException pour(CoureurScanne coureurScanne) {
        return new PassageTropRapprocheException(numeroBoucle, delaiMinimal, secondesRestantes, coureurScanne);
    }
}
