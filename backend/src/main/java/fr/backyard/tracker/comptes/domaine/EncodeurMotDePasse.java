package fr.backyard.tracker.comptes.domaine;

/** Port sortant : calcule et vérifie l'empreinte non réversible d'un mot de passe. */
@FunctionalInterface
public interface EncodeurMotDePasse {

    String encoder(MotDePasse motDePasse);

    /**
     * Vérifie une saisie de connexion contre une empreinte. La saisie n'est pas un {@link MotDePasse} :
     * aucune règle de format ne s'applique à la connexion.
     *
     * <p>Méthode par défaut uniquement pour que l'interface reste fonctionnelle (encodeurs réduits à
     * {@link #encoder}) : tout adaptateur utilisé pour la connexion la redéfinit.
     *
     * @throws UnsupportedOperationException si l'encodeur ne sait pas vérifier
     */
    default boolean verifier(String motDePasseEnClair, String empreinte) {
        throw new UnsupportedOperationException("Cet encodeur ne sait pas vérifier un mot de passe.");
    }
}
