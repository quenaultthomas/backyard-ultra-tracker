package fr.backyard.tracker.courses.domaine;

/** Aucun logo pour cet identifiant de Course (Course sans logo ou inconnue, sans distinction). */
public class LogoIntrouvableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public LogoIntrouvableException() {
        super("Logo introuvable");
    }
}
