package fr.backyard.tracker.courses.domaine;

/** Aucune Inscription de la Course n'a ce jeton. Le message ne reprend jamais le jeton. */
public class JetonInconnuException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public JetonInconnuException() {
        super("Jeton inconnu pour cette course");
    }
}
