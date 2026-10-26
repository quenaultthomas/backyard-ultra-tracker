package fr.backyard.tracker.comptes.domaine;

import java.util.List;

/**
 * Durée d'inactivité de session hors borne, ou rôles privilégiés plus longs que les autres. Le message nomme
 * le ou les paramètres fautifs, également exposés par {@link #parametres()}.
 */
public class PolitiqueSessionInvalideException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String[] parametres;

    public PolitiqueSessionInvalideException(String message, List<String> parametres) {
        super(message);
        this.parametres = parametres.toArray(String[]::new);
    }

    /** Noms des paramètres de {@link PolitiqueSession} en cause (un seul, ou les deux pour la règle d'ordre). */
    public List<String> parametres() {
        return List.of(parametres);
    }
}
