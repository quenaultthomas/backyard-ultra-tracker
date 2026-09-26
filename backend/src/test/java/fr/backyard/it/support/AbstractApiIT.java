package fr.backyard.it.support;

import fr.backyard.domain.Passage;
import fr.backyard.domain.Runner;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.testsupport.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base des tests d'intégration backend (mode rattrapage, INC-1 à INC-3) : contexte Spring complet
 * (contrôleurs, sécurité réelle, services, persistance H2 via Flyway), profil "test", horloge de test
 * contrôlable ({@link MutableClock}, remise à zéro avant chaque test), et nettoyage des données créées.
 *
 * <p>Contrairement aux tests de slice de {@code fr.backyard.api} (services mockés) et aux tests
 * unitaires de service (repositories mockés), ces tests exercent la chaîne complète HTTP -> sécurité
 * -> service -> JPA -> H2, avec de vrais en-têtes {@code Authorization}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AbstractApiIT.TestClockConfig.class)
public abstract class AbstractApiIT {

    /** Instant de référence réinitialisé avant chaque test (RG1 inc. 2 : horloge injectée, jamais Instant.now()). */
    protected static final Instant DEFAULT_INSTANT = Instant.parse("2026-10-03T08:00:00Z");

    /** admin-test / admin-secret (comptes de test, RG32 inc. 3). */
    protected static final String ADMIN = "Basic YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=";
    /** scanner-test / scanner-secret. */
    protected static final String SCANNER = "Basic c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected MutableClock clock;

    @Autowired
    protected RaceRepository raceRepository;

    @Autowired
    protected RunnerRepository runnerRepository;

    @Autowired
    protected PassageRepository passageRepository;

    private final List<Long> createdRaceIds = new ArrayList<>();

    /** Horloge de test unique du contexte (bean partagé entre tests : toujours remise à {@link #DEFAULT_INSTANT}). */
    @TestConfiguration(proxyBeanMethods = false)
    public static class TestClockConfig {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock(DEFAULT_INSTANT);
        }
    }

    @BeforeEach
    void resetClock() {
        clock.set(DEFAULT_INSTANT);
    }

    /** Enregistre l'id d'une course créée par le test pour suppression automatique en fin de test. */
    protected void trackRaceForCleanup(Long raceId) {
        createdRaceIds.add(raceId);
    }

    /** Nettoyage déterministe : passages puis coureurs puis courses, dans l'ordre imposé par les FK RESTRICT. */
    @AfterEach
    void cleanUpCreatedRaces() {
        for (Long raceId : createdRaceIds) {
            List<Runner> runners = runnerRepository.findByRaceId(raceId);
            for (Runner runner : runners) {
                List<Passage> passages = passageRepository.findByRunnerId(runner.getId());
                passageRepository.deleteAll(passages);
            }
            runnerRepository.deleteAll(runners);
            raceRepository.findById(raceId).ifPresent(raceRepository::delete);
        }
        createdRaceIds.clear();
    }

    /** Crée une course au statut SETUP via l'API admin réelle et renvoie son id (extrait de Location). */
    protected Long createSetupRace(String name) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"raceDate\":\"2026-10-03\","
            + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";
        String location = mvc.perform(post("/api/admin/races")
                .header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader("Location");
        return extractId(location);
    }

    /** Inscrit un coureur via l'API publique réelle et renvoie son id (extrait de Location). */
    protected Long register(Long raceId, String name) throws Exception {
        String location = mvc.perform(post("/api/public/races/" + raceId + "/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\"}"))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getHeader("Location");
        return extractId(location);
    }

    /** Positionne l'horloge de test à l'instant de départ puis démarre la course via l'API admin réelle. */
    protected void startRace(Long raceId, String startedAtIso) throws Exception {
        clock.set(Instant.parse(startedAtIso));
        mvc.perform(post("/api/admin/races/" + raceId + "/start").header("Authorization", ADMIN))
            .andExpect(status().isOk());
    }

    /** Jeton QR réel d'un coureur déjà inscrit (relu en base, jamais généré à la main). */
    protected String runnerToken(Long runnerId) {
        return runnerRepository.findById(runnerId).orElseThrow().getQrToken();
    }

    /** Enregistre un scan réel (compte SCANNER) et attend un succès 200. */
    protected void scan(String qrToken, String scannedAtIso) throws Exception {
        mvc.perform(post("/api/scan/passages")
                .header("Authorization", SCANNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrToken\":\"" + qrToken + "\",\"scannedAt\":\"" + scannedAtIso + "\"}"))
            .andExpect(status().isOk());
    }

    private static Long extractId(String location) {
        return Long.valueOf(location.substring(location.lastIndexOf('/') + 1));
    }
}
