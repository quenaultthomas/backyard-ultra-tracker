package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.InitialiserAdminMaster;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contrat 7.3 RG3 (CA9, CA10, CA11) : plafond de hachage par adresse cliente (1 calcul simultané par défaut) en plus du
 * plafond global (4). Le proxy de confiance est le pair de test ({@code localhost}) : l'adresse cliente est celle du
 * {@code X-Forwarded-For}. Le calcul de vérification est ralenti (200 ms) et compté par un espion du
 * {@link PasswordEncoder}. Aucune propriété {@code concurrence-max-par-ip} : la valeur par défaut (1) est celle testée.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "backyard.admin-master.pseudo=Patron", "backyard.admin-master.mot-de-passe=mot-de-passe-patron-1",
        "backyard.limite-debit-public.proxy-de-confiance=localhost",
        "backyard.hachage.attente-max-millisecondes=300"})
@Testcontainers
class HachageParAdresseIntegrationTest {

    static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    static final String CORPS = "{\"pseudo\":\"%s\",\"motDePasse\":\"%s\"}";
    static final String ADRESSE_ATTAQUANT = "203.0.113.1";
    static final String ADRESSE_ADMIN = "203.0.113.2";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.11-alpine");

    @LocalServerPort
    int port;

    @Autowired
    DataSource dataSource;

    @Autowired
    RegistreTentativesConnexionEnMemoire registre;

    @Autowired
    InitialiserAdminMaster initialiserAdminMaster;

    @MockitoSpyBean
    PasswordEncoder argon2;

    final JsonMapper json = JsonMapper.builder().build();
    final AtomicInteger simultanes = new AtomicInteger();
    final AtomicInteger maxSimultanes = new AtomicInteger();
    final AtomicReference<ThrowingRunnable> comportement = new AtomicReference<>();
    ApiHttp api;
    JournalApplicatif journal;

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @BeforeEach
    void preparer() throws Exception {
        new JdbcTemplate(dataSource).update("delete from compte");
        registre.vider();
        initialiserAdminMaster.executer("Patron", "mot-de-passe-patron-1");
        api = new ApiHttp(port);
        doAnswer(appel -> {
            if ("mot-de-passe-patron-1".equals(appel.getArgument(0).toString())) {
                // Connexion de l'admin master (autre adresse) : hors du décompte de l'adresse en rafale.
                return appel.callRealMethod();
            }
            maxSimultanes.accumulateAndGet(simultanes.incrementAndGet(), Math::max);
            try {
                ThrowingRunnable action = comportement.get();
                if (action != null) {
                    action.run();
                }
                return appel.callRealMethod();
            } finally {
                simultanes.decrementAndGet();
            }
        }).when(argon2).matches(any(), anyString());
        journal = JournalApplicatif.demarrer();
        journal.rattacher();
        creerCompte("Alice");
    }

    @AfterEach
    void nettoyer() {
        reset(argon2);
        journal.fermer();
    }

    @Test
    @DisplayName("CA9 : une adresse en rafale de 12 connexions n'a jamais plus d'un calcul simultané (503 SERVICE_SATURE pour le reste, 401 pour les autres), l'admin master d'une autre adresse se connecte en moins de 1 s, la même adresse est servie après la rafale")
    void ca9_rafale_d_une_adresse_n_empeche_pas_les_autres() throws Exception {
        comportement.set(() -> Thread.sleep(200));
        List<ApiHttp.Jeton> jetons = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            jetons.add(api.jetonValide());
        }
        ApiHttp.Jeton jetonAdmin = api.jetonValide();

        List<CompletableFuture<HttpResponse<String>>> rafale = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ApiHttp.Jeton jeton = jetons.get(i);
            String pseudo = "Inconnu" + i;
            rafale.add(CompletableFuture.supplyAsync(() -> connecter(ADRESSE_ATTAQUANT, jeton, pseudo, MOT_DE_PASSE)));
        }
        long limite = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (simultanes.get() < 1 && System.nanoTime() < limite) {
            Thread.sleep(5);
        }
        assertThat(simultanes.get()).as("la rafale a démarré").isGreaterThanOrEqualTo(1);

        long debut = System.nanoTime();
        HttpResponse<String> admin = connecter(ADRESSE_ADMIN, jetonAdmin, "Patron", "mot-de-passe-patron-1");
        long dureeMillisecondes = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - debut);

        List<Integer> statuts = new ArrayList<>();
        for (CompletableFuture<HttpResponse<String>> reponse : rafale) {
            HttpResponse<String> obtenue = reponse.get(30, TimeUnit.SECONDS);
            statuts.add(obtenue.statusCode());
            if (obtenue.statusCode() == 503) {
                assertThat(json.readTree(obtenue.body()).get("code").asString()).isEqualTo("SERVICE_SATURE");
            }
        }
        assertThat(maxSimultanes.get()).as("calculs simultanés maximum observés").isEqualTo(1);
        assertThat(statuts).containsOnly(401, 503).contains(503, 401);
        assertThat(admin.statusCode()).as(admin.body()).isEqualTo(200);
        assertThat(dureeMillisecondes).as("durée de la connexion de l'admin master").isLessThan(1000);

        comportement.set(null);
        HttpResponse<String> apres = connecter(ADRESSE_ATTAQUANT, api.jetonValide(), "Alice", MOT_DE_PASSE);
        assertThat(apres.statusCode()).as(apres.body()).isEqualTo(200);
        assertThat(avertissements()).isNotEmpty().allSatisfy(m -> assertThat(m).contains("plafond par adresse"));
    }

    @Test
    @DisplayName("CA10 : sous saturation d'une adresse, un pseudo existant et un pseudo inconnu reçoivent le même 503 (statut, Retry-After, corps hors instance), aucun échec compté, aucun mot de passe ni pseudo dans les journaux")
    void ca10_meme_refus_pour_pseudo_existant_et_inconnu() throws Exception {
        CountDownLatch enCours = new CountDownLatch(1);
        CountDownLatch liberer = new CountDownLatch(1);
        comportement.set(() -> {
            enCours.countDown();
            liberer.await(15, TimeUnit.SECONDS);
        });
        ApiHttp.Jeton jetonOccupant = api.jetonValide();
        CompletableFuture<HttpResponse<String>> occupant = CompletableFuture.supplyAsync(
                () -> connecter(ADRESSE_ATTAQUANT, jetonOccupant, "Alice", MOT_DE_PASSE));
        assertThat(enCours.await(10, TimeUnit.SECONDS)).isTrue();
        int marque = journal.marque();

        List<HttpResponse<String>> refusExistant = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            refusExistant.add(connecter(ADRESSE_ATTAQUANT, api.jetonValide(), "Alice", MOT_DE_PASSE));
        }
        HttpResponse<String> refusInconnu = connecter(ADRESSE_ATTAQUANT, api.jetonValide(), "Fantome", MOT_DE_PASSE);
        liberer.countDown();

        for (HttpResponse<String> refus : refusExistant) {
            assertThat(refus.statusCode()).isEqualTo(503);
            assertThat(refus.headers().firstValue("retry-after")).hasValue("2");
            assertThat(sansInstance(refus.body())).isEqualTo(sansInstance(refusInconnu.body()));
        }
        assertThat(refusInconnu.statusCode()).isEqualTo(503);
        assertThat(refusInconnu.headers().firstValue("retry-after")).hasValue("2");
        JsonNode corps = json.readTree(refusInconnu.body());
        assertThat(corps.get("code").asString()).isEqualTo("SERVICE_SATURE");
        assertThat(corps.get("reessayerDansSecondes").asInt()).isEqualTo(2);
        assertThat(corps.get("detail").asString())
                .isEqualTo("Le service est momentanément saturé. Réessayez dans quelques secondes.");

        assertThat(occupant.get(15, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        comportement.set(null);
        // 7 refus pour saturation avec le pseudo existant (seuil de blocage : 5) : aucun n'est un échec compté.
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> valide = connecter(ADRESSE_ATTAQUANT, api.jetonValide(), "Alice", MOT_DE_PASSE);
            assertThat(valide.statusCode()).as("connexion valide " + i + " : " + valide.body()).isEqualTo(200);
        }
        List<String> sortie = new ArrayList<>(journal.depuis(marque).stream()
                .map(ILoggingEvent::getFormattedMessage).toList());
        sortie.addAll(journal.messagesEtExceptions());
        assertThat(String.join("\n", sortie)).doesNotContain(MOT_DE_PASSE).doesNotContain("Alice")
                .doesNotContain("Fantome");
    }

    // ---------------------------------------------------------------- utilitaires

    private List<String> avertissements() {
        return journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("saturés"))
                .toList();
    }

    private String sansInstance(String corps) {
        ObjectNode noeud = (ObjectNode) json.readTree(corps);
        noeud.remove("instance");
        return noeud.toString();
    }

    private void creerCompte(String pseudo) throws Exception {
        HttpResponse<String> reponse = api.requete("POST", "/api/comptes", entetes("198.51.100.7", api.jetonValide()),
                "application/json", CORPS.formatted(pseudo, MOT_DE_PASSE));
        assertThat(reponse.statusCode()).as(reponse.body()).isEqualTo(201);
    }

    private Map<String, String> entetes(String adresse, ApiHttp.Jeton jeton) {
        return Map.of("Cookie", "XSRF-TOKEN=" + jeton.cookie(), "X-XSRF-TOKEN", jeton.entete(),
                "X-Forwarded-For", adresse);
    }

    private HttpResponse<String> connecter(String adresse, ApiHttp.Jeton jeton, String pseudo, String motDePasse) {
        try {
            return api.requete("POST", "/api/connexion", entetes(adresse, jeton), "application/json",
                    CORPS.formatted(pseudo, motDePasse));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
