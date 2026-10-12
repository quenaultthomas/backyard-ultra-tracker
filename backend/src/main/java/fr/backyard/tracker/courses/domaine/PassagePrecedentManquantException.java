package fr.backyard.tracker.courses.domaine;

/** L'Inscription n'a pas de Passage sur la Boucle précédant celle du scan. */
public class PassagePrecedentManquantException extends RefusPassageCoureurException {

    private static final long serialVersionUID = 1L;

    private final int numeroBoucle;

    public PassagePrecedentManquantException(int numeroBoucle) {
        this(numeroBoucle, null);
    }

    public PassagePrecedentManquantException(int numeroBoucle, CoureurScanne coureur) {
        super("Passage précédent manquant pour la boucle " + numeroBoucle, coureur);
        this.numeroBoucle = numeroBoucle;
    }

    /** Boucle visée par le scan. */
    public int numeroBoucle() {
        return numeroBoucle;
    }

    @Override
    public PassagePrecedentManquantException pour(CoureurScanne coureurScanne) {
        return new PassagePrecedentManquantException(numeroBoucle, coureurScanne);
    }
}
