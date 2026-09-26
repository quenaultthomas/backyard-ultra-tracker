package fr.backyard.it;

import fr.backyard.domain.Race;
import fr.backyard.it.support.AbstractApiIT;
import fr.backyard.service.YardClosingResult;
import fr.backyard.service.YardClosingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mode rattrapage INC-2 et INC-3 : scénario de bout en bout demandé explicitement par l'orchestrateur
 * (création de course -> inscription -> démarrage -> scan -> clôture de yard par le service avec une
 * Clock contrôlée -> auto-DNF -> réintégration -> tableau de bord), avec un contexte Spring complet
 * (sécurité réelle, services réels, persistance réelle H2 via Flyway). Aucun test existant ne fait
 * collaborer {@code YardClosingService} (INC-2) avec la vraie chaîne HTTP de l'API (INC-3) et une vraie
 * base : les tests de service de l'incrément 2 mockent les repositories, les tests de slice de
 * l'incrément 3 mockent les services.
 */
@Tag("INC-2")
@Tag("INC-3")
class EndToEndRaceLifecycleIT extends AbstractApiIT {

    private static final Instant STARTED_AT = Instant.parse("2026-10-03T08:00:00Z");

    @Autowired
    private YardClosingService yardClosingService;

    @Test
    @Tag("INC2-CA27")
    @Tag("INC2-CA44")
    @Tag("INC3-CA19")
    @Tag("INC3-CA32")
    @Tag("INC3-CA37")
    @Tag("INC3-CA40")
    @DisplayName("Scenario bout en bout - creation, inscription, demarrage, scan, cloture de yard "
        + "(auto-DNF reel), reintegration, puis tableau de bord coherent, a travers la chaine HTTP -> "
        + "securite -> service -> JPA -> H2")
    void raceLifecycleFromCreationToDashboardAfterAutoDnfAndReintegration() throws Exception {
        // ── Etape 1 : creation de la course (E7, RG9) ────────────────────────────────────────────
        Long raceId = createSetupRace("IT E2E Backyard");
        trackRaceForCleanup(raceId);

        // ── Etape 2 : inscription publique de trois coureurs (E3, RG15) ─────────────────────────
        Long aliceId = register(raceId, "Alice");
        Long bobId = register(raceId, "Bob");
        Long charlieId = register(raceId, "Charlie");
        String aliceToken = runnerToken(aliceId);
        String bobToken = runnerToken(bobId);
        String charlieToken = runnerToken(charlieId);

        // ── Etape 3 : demarrage de la course (E12, RG13) ─────────────────────────────────────────
        startRace(raceId, "2026-10-03T08:00:00Z");
        mvc.perform(get("/api/admin/races/" + raceId).header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.startedAt").value("2026-10-03T08:00:00Z"));

        // ── Etape 4 : scans du yard 1 par Alice et Charlie ; Bob ne scanne pas (E6, RG20) ────────
        clock.set(Instant.parse("2026-10-03T08:45:00Z"));
        scan(aliceToken, "2026-10-03T08:45:00Z");
        clock.set(Instant.parse("2026-10-03T08:50:00Z"));
        scan(charlieToken, "2026-10-03T08:50:00Z");

        // ── Etape 5 : cloture du yard 1 par le service, avec une Clock controlee (RG16 a RG19) ──
        clock.set(Instant.parse("2026-10-03T09:00:00Z"));
        Race race = raceRepository.findById(raceId).orElseThrow();
        YardClosingResult result = yardClosingService.closeYard(race);

        assertThat(result.closedYard()).isEqualTo(1);
        assertThat(result.timedOutRunnerIds()).containsExactly(bobId);
        assertThat(result.winnerRunnerId()).isEmpty();
        assertThat(result.raceFinished()).isFalse();
        mvc.perform(get("/api/admin/runners/" + bobId).header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DNF"))
            .andExpect(jsonPath("$.dnfReason").value("TIMEOUT"))
            .andExpect(jsonPath("$.dnfYard").value(1));

        // ── Etape 6 : reintegration admin de Bob (E18, RG22 a RG25) ──────────────────────────────
        mvc.perform(post("/api/admin/runners/" + bobId + "/reintegration").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runner.status").value("ACTIVE"))
            .andExpect(jsonPath("$.runner.dnfReason").value(nullValue()))
            .andExpect(jsonPath("$.runner.dnfYard").value(nullValue()))
            .andExpect(jsonPath("$.recreatedPassages", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.recreatedPassages[0].yardNumber").value(1))
            .andExpect(jsonPath("$.recreatedPassages[0].source").value("MANUAL"))
            .andExpect(jsonPath("$.recreatedPassages[0].corrected").value(true));

        // ── Etape 7 : le tableau de bord public reflete un etat coherent pour les trois coureurs
        // (E4, RG24, RG27), via une vraie requete groupee (findByRunnerRaceId) ───────────────────
        mvc.perform(get("/api/public/races/" + raceId + "/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.race.status").value("RUNNING"))
            .andExpect(jsonPath("$.currentYard").value(2))
            .andExpect(jsonPath("$.currentYardEndsAt").value("2026-10-03T10:00:00Z"))
            .andExpect(jsonPath("$.runners", org.hamcrest.Matchers.hasSize(3)))
            // Alice (bib 1) : scan reel yard 1, allure calculee, non corrigee
            .andExpect(jsonPath("$.runners[0].bib").value(1))
            .andExpect(jsonPath("$.runners[0].name").value("Alice"))
            .andExpect(jsonPath("$.runners[0].status").value("ACTIVE"))
            .andExpect(jsonPath("$.runners[0].completedLoops").value(1))
            .andExpect(jsonPath("$.runners[0].distanceMeters").value(6706))
            .andExpect(jsonPath("$.runners[0].elevationMeters").value(50))
            .andExpect(jsonPath("$.runners[0].averagePaceSecondsPerKm").value(403))
            .andExpect(jsonPath("$.runners[0].corrected").value(false))
            // Bob (bib 2) : reintegre, passage MANUAL, allure non definie, badge corrige
            .andExpect(jsonPath("$.runners[1].bib").value(2))
            .andExpect(jsonPath("$.runners[1].name").value("Bob"))
            .andExpect(jsonPath("$.runners[1].status").value("ACTIVE"))
            .andExpect(jsonPath("$.runners[1].dnfReason").value(nullValue()))
            .andExpect(jsonPath("$.runners[1].completedLoops").value(1))
            .andExpect(jsonPath("$.runners[1].distanceMeters").value(6706))
            .andExpect(jsonPath("$.runners[1].averagePaceSecondsPerKm").value(nullValue()))
            .andExpect(jsonPath("$.runners[1].corrected").value(true))
            // Charlie (bib 3) : scan reel yard 1, allure calculee, non corrige
            .andExpect(jsonPath("$.runners[2].bib").value(3))
            .andExpect(jsonPath("$.runners[2].name").value("Charlie"))
            .andExpect(jsonPath("$.runners[2].completedLoops").value(1))
            .andExpect(jsonPath("$.runners[2].averagePaceSecondsPerKm").value(447))
            .andExpect(jsonPath("$.runners[2].corrected").value(false));

        // ── Etape 8 : le detail public de Bob confirme le badge corrige et l'absence de temps de
        // boucle pour son passage recree (RG26, meme calculateur que le tableau de bord, RG27) ────
        mvc.perform(get("/api/public/runners/" + bobId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.passages", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.passages[0].source").value("MANUAL"))
            .andExpect(jsonPath("$.passages[0].scannedAt").value(nullValue()))
            .andExpect(jsonPath("$.passages[0].loopTimeMillis").value(nullValue()))
            .andExpect(jsonPath("$.passages[0].corrected").value(true));
    }
}
