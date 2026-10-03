package fr.backyard.tracker.courses.domaine;

/** Contenu refusé comme logo de Course. Le message ne reprend jamais les octets reçus. */
public class LogoInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Cause du refus, dans l'ordre de contrôle. */
    public enum Motif {
        REQUIS,
        TROP_VOLUMINEUX,
        FORMAT_INVALIDE
    }

    private final Motif motif;

    public LogoInvalideException(Motif motif) {
        super("Logo invalide : " + motif);
        this.motif = motif;
    }

    public Motif motif() {
        return motif;
    }
}
