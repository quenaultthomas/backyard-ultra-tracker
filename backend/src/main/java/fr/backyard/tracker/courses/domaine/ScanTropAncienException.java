package fr.backyard.tracker.courses.domaine;

/** Le scan a eu lieu plus de {@link AgeDuScan#AGE_MAXIMAL_D_UN_SCAN} avant son envoi : aucun Passage n'est créé. */
public class ScanTropAncienException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ScanTropAncienException() {
        super("Scan trop ancien pour être enregistré");
    }
}
