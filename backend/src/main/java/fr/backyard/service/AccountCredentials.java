package fr.backyard.service;

/**
 * Données d'authentification d'un compte coureur (RG9 inc. 5), lues à chaque requête.
 *
 * @param accountId    identifiant du compte
 * @param pseudo       pseudo stocké (normalisé)
 * @param passwordHash hash BCrypt, jamais le mot de passe en clair ; masqué par {@link #toString()}
 */
public record AccountCredentials(Long accountId, String pseudo, String passwordHash) {

    @Override
    public String toString() {
        return "AccountCredentials[accountId=" + accountId + ", passwordHash=***]";
    }
}
