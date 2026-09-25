package fr.backyard.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Les deux comptes de l'API (RG28, RG32 inc. 3), liés aux variables d'environnement
 * {@code BACKYARD_SECURITY_*}. Aucune valeur par défaut : {@link #validate()} refuse une configuration
 * incomplète, et ses messages nomment la variable concernée sans jamais citer sa valeur.
 */
@ConfigurationProperties(prefix = "backyard.security")
public record BackyardSecurityProperties(Account admin, Account scanner) {

    static final String ADMIN_USERNAME_VARIABLE = "BACKYARD_SECURITY_ADMIN_USERNAME";
    static final String ADMIN_PASSWORD_HASH_VARIABLE = "BACKYARD_SECURITY_ADMIN_PASSWORD_HASH";
    static final String SCANNER_USERNAME_VARIABLE = "BACKYARD_SECURITY_SCANNER_USERNAME";
    static final String SCANNER_PASSWORD_HASH_VARIABLE = "BACKYARD_SECURITY_SCANNER_PASSWORD_HASH";

    /** Hash BCrypt : préfixe $2a$, $2b$ ou $2y$, coût à deux chiffres, 53 caractères de sel et d'empreinte. */
    private static final Pattern BCRYPT_HASH = Pattern.compile("^\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}$");

    /**
     * Compte d'accès à l'API.
     *
     * @param username     nom du compte
     * @param passwordHash hash BCrypt du mot de passe, jamais le mot de passe en clair
     */
    public record Account(String username, String passwordHash) {

        @Override
        public String toString() {
            return "Account[username=" + username + ", passwordHash=***]";
        }
    }

    /**
     * Refuse le démarrage si une valeur manque ou est vide, si un hash n'est pas au format BCrypt,
     * ou si les deux comptes portent le même nom.
     */
    public void validate() {
        String adminUsername = requireValue(admin == null ? null : admin.username(), ADMIN_USERNAME_VARIABLE);
        requireBcryptHash(admin.passwordHash(), ADMIN_PASSWORD_HASH_VARIABLE);
        String scannerUsername = requireValue(scanner == null ? null : scanner.username(),
            SCANNER_USERNAME_VARIABLE);
        requireBcryptHash(scanner.passwordHash(), SCANNER_PASSWORD_HASH_VARIABLE);
        if (Objects.equals(adminUsername, scannerUsername)) {
            throw new IllegalStateException("Variables d'environnement " + ADMIN_USERNAME_VARIABLE + " et "
                + SCANNER_USERNAME_VARIABLE + " : les noms de compte ADMIN et SCANNER sont identiques,"
                + " ils doivent être distincts");
        }
    }

    private static String requireValue(String value, String variable) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Variable d'environnement " + variable + " manquante ou vide");
        }
        return value;
    }

    private static void requireBcryptHash(String value, String variable) {
        requireValue(value, variable);
        if (!BCRYPT_HASH.matcher(value).matches()) {
            throw new IllegalStateException("Variable d'environnement " + variable
                + " invalide : un hash BCrypt est attendu ($2a$, $2b$ ou $2y$, coût à deux chiffres,"
                + " 60 caractères), jamais le mot de passe en clair");
        }
    }
}
