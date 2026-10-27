package fr.backyard.tracker.comptes.integration;

import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.MOT_DE_PASSE;
import static fr.backyard.tracker.comptes.integration.ClientConnexionParAdresse.corps;

import fr.backyard.tracker.comptes.integration.ApiHttp.Jeton;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outils de test du plafond de créations de Comptes par adresse (8.5) : {@code POST /api/comptes} avec un
 * {@code X-Forwarded-For} choisi par le test (nul : aucun en-tête), jeton CSRF valide obtenu une fois.
 */
final class ClientCreationCompte {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private final ClientConnexionParAdresse client;

    ClientCreationCompte(ApiHttp api) throws Exception {
        this.client = new ClientConnexionParAdresse(api);
    }

    /** Pseudo valide (3 à 30 caractères) et unique pour toute la JVM de test. */
    static String pseudoUnique() {
        return "Coureur-" + SEQUENCE.incrementAndGet();
    }

    HttpResponse<String> creer(String adresse, String pseudo) throws Exception {
        return creer(adresse, pseudo, MOT_DE_PASSE);
    }

    HttpResponse<String> creer(String adresse, String pseudo, String motDePasse) throws Exception {
        return client.envoyer("/api/comptes", adresse, corps(pseudo, motDePasse), client.jeton());
    }

    HttpResponse<String> creerAvecUnPseudoNeuf(String adresse) throws Exception {
        return creer(adresse, pseudoUnique());
    }

    HttpResponse<String> envoyer(String adresse, String corpsBrut, Jeton jeton) throws Exception {
        return client.envoyer("/api/comptes", adresse, corpsBrut, jeton);
    }

    ClientConnexionParAdresse connexion() {
        return client;
    }

    Jeton jeton() {
        return client.jeton();
    }
}
