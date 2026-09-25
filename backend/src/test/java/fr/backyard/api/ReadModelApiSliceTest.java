package fr.backyard.api;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.service.PassageView;
import fr.backyard.service.RaceBoardView;
import fr.backyard.service.RunnerBoardEntry;
import fr.backyard.service.RunnerDetailView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec increment 3 - RG3, RG16, RG24, RG26, CA27, CA42, CA44 (partie [slice]). */
class ReadModelApiSliceTest extends ApiSliceTest {

    private static final String SECRET_TOKEN = "9d1e7c55-0a4b-4f2e-8c3d-77aa00bb11cc";

    private static RaceBoardView boardOfCa40() {
        Race r1 = backyardTest(RaceStatus.RUNNING);
        return new RaceBoardView(r1, at("10:20:00"), 3, at("11:00:00"), List.of(
            new RunnerBoardEntry(1L, 1, "A", RunnerStatus.ACTIVE, null, null, 2, 13_412L, 100L,
                OptionalInt.of(425), false),
            new RunnerBoardEntry(2L, 2, "B", RunnerStatus.DNF, DnfReason.VOLUNTARY, 2, 1, 6_706L, 50L,
                OptionalInt.of(403), false)));
    }

    private static RaceBoardView boardOfCa41() {
        return new RaceBoardView(backyardTest(RaceStatus.SETUP), at("07:00:00"), 0, null, List.of(
            new RunnerBoardEntry(1L, 1, "A", RunnerStatus.ACTIVE, null, null, 0, 0L, 0L, OptionalInt.empty(), false)));
    }

    private static RunnerDetailView detailOfCa44() {
        return new RunnerDetailView(12L, 1L, 6, "Alice", RunnerStatus.ACTIVE, null, null, 2, 13_412L, 100L,
            OptionalInt.of(403), true, List.of(
                new PassageView(1, PassageSource.SCAN, at("08:45:00"), OptionalLong.of(2_700_000L), false),
                new PassageView(2, PassageSource.MANUAL, null, OptionalLong.empty(), true)));
    }

    @Test
    @DisplayName("CA42 - tableau de bord : serverTime, currentYard 3, currentYardEndsAt 11:00Z, allure 425, dnfReason VOLUNTARY")
    void ca42_boardOfRunningRace() throws Exception {
        when(raceBoardService.board(1L)).thenReturn(boardOfCa40());

        mvc.perform(get("/api/public/races/1/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.race.id").value(1))
            .andExpect(jsonPath("$.race.status").value("RUNNING"))
            .andExpect(jsonPath("$.serverTime").value("2026-10-03T10:20:00Z"))
            .andExpect(jsonPath("$.currentYard").value(3))
            .andExpect(jsonPath("$.currentYardEndsAt").value("2026-10-03T11:00:00Z"))
            .andExpect(jsonPath("$.runners[0].runnerId").value(1))
            .andExpect(jsonPath("$.runners[0].averagePaceSecondsPerKm").value(425))
            .andExpect(jsonPath("$.runners[0].distanceMeters").value(13412))
            .andExpect(jsonPath("$.runners[0].corrected").value(false))
            .andExpect(jsonPath("$.runners[1].status").value("DNF"))
            .andExpect(jsonPath("$.runners[1].dnfReason").value("VOLUNTARY"))
            .andExpect(jsonPath("$.runners[1].dnfYard").value(2));
    }

    @Test
    @DisplayName("CA42 - tableau de bord SETUP : currentYardEndsAt et averagePaceSecondsPerKm presents a null")
    void ca42_boardOfSetupRace() throws Exception {
        when(raceBoardService.board(1L)).thenReturn(boardOfCa41());

        mvc.perform(get("/api/public/races/1/board"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.currentYard").value(0))
            .andExpect(jsonPath("$").value(hasKey("currentYardEndsAt")))
            .andExpect(jsonPath("$.currentYardEndsAt").value(nullValue()))
            .andExpect(jsonPath("$.runners[0]").value(hasKey("averagePaceSecondsPerKm")))
            .andExpect(jsonPath("$.runners[0].averagePaceSecondsPerKm").value(nullValue()))
            .andExpect(jsonPath("$.runners[0]").value(hasKey("dnfReason")))
            .andExpect(jsonPath("$.race").value(hasKey("startedAt")))
            .andExpect(jsonPath("$.race.startedAt").value(nullValue()));
    }

    @Test
    @DisplayName("CA44 - detail public d'un coureur : passages[1].loopTimeMillis null (cle presente), passages[1].corrected true")
    void ca44_publicRunnerDetail() throws Exception {
        when(raceBoardService.runnerDetail(12L)).thenReturn(detailOfCa44());

        mvc.perform(get("/api/public/runners/12"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runnerId").value(12))
            .andExpect(jsonPath("$.raceId").value(1))
            .andExpect(jsonPath("$.averagePaceSecondsPerKm").value(403))
            .andExpect(jsonPath("$.corrected").value(true))
            .andExpect(jsonPath("$.passages[0].loopTimeMillis").value(2_700_000))
            .andExpect(jsonPath("$.passages[0].scannedAt").value("2026-10-03T08:45:00Z"))
            .andExpect(jsonPath("$.passages[1]").value(hasKey("loopTimeMillis")))
            .andExpect(jsonPath("$.passages[1].loopTimeMillis").value(nullValue()))
            .andExpect(jsonPath("$.passages[1].scannedAt").value(nullValue()))
            .andExpect(jsonPath("$.passages[1].corrected").value(true));
    }

    @Test
    @DisplayName("CA27 - le qr_token n'apparait ni dans le tableau de bord, ni dans le detail public, ni dans la reponse de scan")
    void ca27_qrTokenNeverExposedPublicly() throws Exception {
        when(raceBoardService.board(1L)).thenReturn(boardOfCa40());
        when(raceBoardService.runnerDetail(12L)).thenReturn(detailOfCa44());
        Race r1 = backyardTest(RaceStatus.RUNNING);
        when(passageRecordingService.recordScan(SECRET_TOKEN, at("08:45:00")))
            .thenReturn(withId(scan(runner(12L, r1, 6, "Alice", SECRET_TOKEN), 1, at("08:45:00")), 40L));

        String board = mvc.perform(get("/api/public/races/1/board"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String detail = mvc.perform(get("/api/public/runners/12"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String scanResponse = mvc.perform(post("/api/scan/passages").header("Authorization", SCANNER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"qrToken\":\"" + SECRET_TOKEN + "\",\"scannedAt\":\"2026-10-03T08:45:00Z\"}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        for (String body : List.of(board, detail, scanResponse)) {
            assertThat(body).doesNotContain("qrToken").doesNotContain(SECRET_TOKEN);
        }
    }
}
