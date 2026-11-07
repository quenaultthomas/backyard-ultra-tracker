package fr.backyard.tracker.comptes.domaine;

/** Contenu refusé comme photo de profil. Le message ne reprend jamais les octets reçus. */
public class PhotoInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Cause du refus, dans l'ordre de contrôle. */
    public enum Motif {
        REQUISE,
        TROP_VOLUMINEUSE,
        FORMAT_INVALIDE
    }

    private final Motif motif;

    public PhotoInvalideException(Motif motif) {
        super("Photo invalide : " + motif);
        this.motif = motif;
    }

    public Motif motif() {
        return motif;
    }
}
