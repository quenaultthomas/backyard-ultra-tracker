package fr.backyard.tracker.comptes.domaine;

/** L'avance demandée ferait dépasser au décalage cumulé de l'horloge la borne de 365 jours. */
public class DecalageHorlogeExcessifException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public DecalageHorlogeExcessifException() {
        super("Le décalage cumulé de l'horloge ne peut pas dépasser 365 jours.");
    }
}
