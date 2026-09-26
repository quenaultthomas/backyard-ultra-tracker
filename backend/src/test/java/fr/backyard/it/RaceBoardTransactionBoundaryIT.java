package fr.backyard.it;

import fr.backyard.domain.Race;
import fr.backyard.it.support.AbstractApiIT;
import fr.backyard.service.YardClosingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mode rattrapage INC-3 : RG2 (frontière de transaction, {@code spring.jpa.open-in-view=false}).
 * {@code RaceBoardServiceTest} et {@code ApiSliceTest} (services mockés) ne peuvent pas observer un vrai
 * chargement paresseux : {@code RaceBoardService.runnerDetail} et {@code describePassages} appellent
 * {@code runner.getRace()} sur l'association {@code Runner.race} (FetchType.LAZY). Ces tests vérifient,
 * avec un contexte Spring complet et une vraie base H2, qu'aucune {@code LazyInitializationException}
 * n'est levée en dehors de la transaction du service, malgré OSIV désactivé.
 */
@Tag("INC-3")
class RaceBoardTransactionBoundaryIT extends AbstractApiIT {

    @Autowired
    private YardClosingService yardClosingService;

    // ── RG2, CA44 — Détail public d'un coureur : runner.getRace() en lecture seule ─────────────────
    @Test
    @Tag("INC3-CA44")
    @DisplayName("RG2/CA44 - GET /api/public/runners/{id} charge l'association paresseuse Runner.race "
        + "dans la transaction du service (200, aucune LazyInitializationException) malgre open-in-view=false")
    void publicRunnerDetailLoadsLazyRaceAssociationWithinItsOwnTransaction() throws Exception {
        Long raceId = createSetupRace("IT RG2 RunnerDetail");
        trackRaceForCleanup(raceId);
        Long runnerId = register(raceId, "Alice");
        startRace(raceId, "2026-10-03T08:00:00Z");

        clock.set(Instant.parse("2026-10-03T08:45:00Z"));
        scan(runnerToken(runnerId), "2026-10-03T08:45:00Z");

        mvc.perform(get("/api/public/runners/" + runnerId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.raceId").value(raceId))
            .andExpect(jsonPath("$.completedLoops").value(1))
            .andExpect(jsonPath("$.passages", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.passages[0].corrected").value(false));
    }

    // ── RG2, CA37 — Réintégration : describePassages() charge runner.getRace() ────────────────────
    @Test
    @Tag("INC3-CA37")
    @DisplayName("RG2/CA37 - POST /api/admin/runners/{id}/reintegration : RaceBoardService.describePassages "
        + "charge l'association paresseuse Runner.race sans erreur (200, badge corrige, temps de boucle nul)")
    void reintegrationDescribePassagesLoadsLazyRaceAssociation() throws Exception {
        Long raceId = createSetupRace("IT RG2 Reintegration");
        trackRaceForCleanup(raceId);
        Long aliceId = register(raceId, "Alice");
        Long carolId = register(raceId, "Carol");
        Long bobId = register(raceId, "Bob");
        startRace(raceId, "2026-10-03T08:00:00Z");

        clock.set(Instant.parse("2026-10-03T08:45:00Z"));
        scan(runnerToken(aliceId), "2026-10-03T08:45:00Z");
        clock.set(Instant.parse("2026-10-03T08:50:00Z"));
        scan(runnerToken(carolId), "2026-10-03T08:50:00Z");
        // Bob ne scanne pas le yard 1 : il sera mis DNF TIMEOUT par la cloture du yard 1.

        clock.set(Instant.parse("2026-10-03T09:00:00Z"));
        Race race = raceRepository.findById(raceId).orElseThrow();
        yardClosingService.closeYard(race);

        mvc.perform(post("/api/admin/runners/" + bobId + "/reintegration").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runner.status").value("ACTIVE"))
            .andExpect(jsonPath("$.recreatedPassages", org.hamcrest.Matchers.hasSize(1)))
            .andExpect(jsonPath("$.recreatedPassages[0].yardNumber").value(1))
            .andExpect(jsonPath("$.recreatedPassages[0].source").value("MANUAL"))
            .andExpect(jsonPath("$.recreatedPassages[0].scannedAt").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.recreatedPassages[0].loopTimeMillis").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.recreatedPassages[0].corrected").value(true));
    }
}
