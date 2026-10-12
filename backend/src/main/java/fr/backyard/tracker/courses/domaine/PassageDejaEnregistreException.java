package fr.backyard.tracker.courses.domaine;

/** L'Inscription a déjà un Passage sur cette Boucle (unique en base). */
public class PassageDejaEnregistreException extends RefusPassageCoureurException {

    private static final long serialVersionUID = 1L;

    private final int numeroBoucle;

    public PassageDejaEnregistreException(int numeroBoucle) {
        this(numeroBoucle, null);
    }

    public PassageDejaEnregistreException(int numeroBoucle, CoureurScanne coureur) {
        super("Passage déjà enregistré sur la boucle " + numeroBoucle, coureur);
        this.numeroBoucle = numeroBoucle;
    }

    /** Boucle visée par le scan. */
    public int numeroBoucle() {
        return numeroBoucle;
    }

    @Override
    public PassageDejaEnregistreException pour(CoureurScanne coureurScanne) {
        return new PassageDejaEnregistreException(numeroBoucle, coureurScanne);
    }
}
