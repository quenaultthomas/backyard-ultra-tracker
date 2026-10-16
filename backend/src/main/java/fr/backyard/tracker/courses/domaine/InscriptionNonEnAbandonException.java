package fr.backyard.tracker.courses.domaine;

/** Seule une Inscription en abandon est réintégrable ; le statut courant est porté (EN_COURSE ou VAINQUEUR). */
public class InscriptionNonEnAbandonException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final StatutInscription statut;

    public InscriptionNonEnAbandonException(StatutInscription statut) {
        super("Inscription non en abandon (statut " + statut + ")");
        this.statut = statut;
    }

    public StatutInscription statut() {
        return statut;
    }
}
