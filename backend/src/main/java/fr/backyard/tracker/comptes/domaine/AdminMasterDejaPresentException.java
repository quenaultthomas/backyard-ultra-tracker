package fr.backyard.tracker.comptes.domaine;

/** Un admin master a déjà été enregistré (création concurrente détectée par la persistance). */
public class AdminMasterDejaPresentException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AdminMasterDejaPresentException(Throwable cause) {
        super("Un admin master existe déjà.", cause);
    }
}
