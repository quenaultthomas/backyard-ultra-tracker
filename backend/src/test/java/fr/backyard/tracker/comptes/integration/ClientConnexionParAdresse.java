package fr.backyard.tracker.comptes.integration;

import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Outils de test du plafond d'échecs de connexion par adresse (8.4) : connexion avec un {@code X-Forwarded-For}
 * choisi par le test, création de Comptes en base. Le jeton CSRF est obtenu une fois et réutilisé.
 */
final class ClientConnexionParAdresse {

    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String MOT_DE_PASSE_FAUX = "mauvais-mot-de-passe-1";
    static final String DETAIL_BLOCAGE = "Trop de tentatives de connexion. Réessayez plus tard.";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final ApiHttp api;
    private final Jeton jeton;

    ClientConnexionParAdresse(ApiHttp api) throws Exception {
        this.api = api;
        this.jeton = api.jetonValide();
    }

    /** Tentative de connexion ; {@code adresse} nulle : aucun {@code X-Forwarded-For}. */
    HttpResponse<String> connecter(String adresse, String pseudo, String motDePasse) throws Exception {
        return envoyer("/api/connexion", adresse, corps(pseudo, motDePasse), jeton);
    }

    HttpResponse<String> envoyer(String chemin, String adresse, String corps, Jeton jetonUtilise) throws Exception {
        Map<String, String> entetes = new HashMap<>();
        if (jetonUtilise != null) {
            entetes.put("Cookie", "XSRF-TOKEN=" + jetonUtilise.cookie());
            entetes.put("X-XSRF-TOKEN", jetonUtilise.entete());
        }
        if (adresse != null) {
            entetes.put("X-Forwarded-For", adresse);
        }
        return api.requete("POST", chemin, entetes, "application/json", corps);
    }

    Jeton jeton() {
        return jeton;
    }

    /** {@code n} échecs d'identification, chacun sur un pseudo inexistant différent. */
    void echouer(String adresse, String prefixe, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            HttpResponse<String> reponse = connecter(adresse, prefixe + i, MOT_DE_PASSE_FAUX);
            if (reponse.statusCode() != 401) {
                throw new AssertionError("Échec " + i + " de " + prefixe + " : attendu 401, reçu "
                        + reponse.statusCode() + " " + reponse.body());
            }
        }
    }

    static String corps(String pseudo, String motDePasse) {
        return "{\"pseudo\":\"" + pseudo + "\",\"motDePasse\":\"" + motDePasse + "\"}";
    }

    static JsonNode lireCorps(HttpResponse<String> reponse) {
        return JSON.readTree(reponse.body());
    }

    static UUID creerCompte(JdbcTemplate jdbc, String pseudo, String role, String empreinte, boolean actif,
                            Instant creeLe) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into compte (id, pseudo, pseudo_normalise, empreinte_mot_de_passe, role, cree_le, actif)
                values (?, ?, ?, ?, ?, ?, ?)""",
                id, pseudo, pseudo.toLowerCase(Locale.ROOT), empreinte, role, Timestamp.from(creeLe), actif);
        return id;
    }
}
