package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/** Outils des tests de durée de session (8.2) : création de comptes, connexions HTTP réelles, attentes bornées. */
final class OutilsSession {

    static final String MOT_DE_PASSE_PATRON = "mot-de-passe-patron-1";
    static final String MOT_DE_PASSE_ADMIN = "mot-de-passe-admin-1";
    static final String MOT_DE_PASSE_BENEVOLE = "mot-de-passe-benevole-1";
    static final String MOT_DE_PASSE_COUREUR = "un-mot-de-passe-12";
    static final String MOT_DE_PASSE_ADMIN_NOUVEAU = "nouveau-mot-de-passe-admin-1";

    private static final String INSERTION = "insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, "
            + "role, cree_le) values (?, ?, ?, ?, ?, ?)";

    /** Cookies d'une session ouverte (JSESSIONID et jeton CSRF). */
    record Session(String id, String xsrf) {
        Map<String, String> entetes() {
            return Map.of("Cookie", "JSESSIONID=" + id + "; XSRF-TOKEN=" + xsrf, "X-XSRF-TOKEN", xsrf);
        }
    }

    private final ApiHttp api;
    private final JdbcTemplate jdbc;
    private final PasswordEncoder encodeur;

    OutilsSession(ApiHttp api, JdbcTemplate jdbc, PasswordEncoder encodeur) {
        this.api = api;
        this.jdbc = jdbc;
        this.encodeur = encodeur;
    }

    UUID inserer(String pseudo, String role, String motDePasse) {
        UUID id = UUID.randomUUID();
        jdbc.update(INSERTION, id, pseudo, pseudo.toLowerCase(Locale.ROOT), encodeur.encode(motDePasse), role,
                Timestamp.from(Instant.parse("2026-09-01T08:30:00Z")));
        return id;
    }

    Session ouvrir(String pseudo, String motDePasse) throws Exception {
        ApiHttp.Jeton jeton = api.jetonValide();
        HttpResponse<String> reponse = connecter(pseudo, motDePasse, null, jeton);
        assertThat(reponse.statusCode()).as("connexion de " + pseudo).isEqualTo(200);
        return new Session(ApiHttp.valeurCookie(reponse, "JSESSIONID"), jeton.cookie());
    }

    /** Connexion, éventuellement en présentant le cookie d'une session préexistante. */
    HttpResponse<String> connecter(String pseudo, String motDePasse, Session preexistante, ApiHttp.Jeton jeton)
            throws Exception {
        String cookie = "XSRF-TOKEN=" + jeton.cookie() + (preexistante == null ? "" : "; JSESSIONID=" + preexistante.id());
        return api.requete("POST", "/api/connexion", Map.of("Cookie", cookie, "X-XSRF-TOKEN", jeton.entete()),
                "application/json", "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}");
    }

    HttpResponse<String> lire(Session session, String chemin) throws Exception {
        return api.requete("GET", chemin, Map.of("Cookie", "JSESSIONID=" + session.id()), null, null);
    }

    HttpResponse<String> lireSansCookie(String chemin) throws Exception {
        return api.requete("GET", chemin, Map.of(), null, null);
    }

    /** Pause réelle : le conteneur compte l'inactivité en temps réel, aucune horloge de test n'agit dessus. */
    static void patienter(long millisecondes) throws InterruptedException {
        Thread.sleep(millisecondes);
    }

    static void attendre(String quoi, BooleanSupplier condition) throws InterruptedException {
        long limite = System.nanoTime() + 10_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("Condition non atteinte en 10 s : " + quoi);
            }
            Thread.sleep(100);
        }
    }
}
