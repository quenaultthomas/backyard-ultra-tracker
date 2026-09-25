package fr.backyard.api;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.service.RaceCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

import java.time.LocalDate;
import java.util.List;

import static fr.backyard.testsupport.TestData.T0;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.namedRace;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec increment 3 - RG3, RG9 a RG14, CA10, CA12, CA13, CA14, CA17, CA19 (parties [slice]). */
class RaceApiSliceTest extends ApiSliceTest {

    private static final String BACKYARD_TEST_BODY = "{\"name\":\"Backyard Test\",\"raceDate\":\"2026-10-03\","
        + "\"loopDistance\":6706,\"loopDuration\":3600,\"loopElevation\":50}";

    @Test
    @DisplayName("CA10 - creation : 201, Location /api/admin/races/1, corps RaceResponse exact (startedAt null present)")
    void ca10_createRace() throws Exception {
        RaceCommand command = new RaceCommand("Backyard Test", LocalDate.of(2026, 10, 3), 6706, 3600, 50);
        when(raceService.create(command)).thenReturn(backyardTest(RaceStatus.SETUP));

        mvc.perform(post("/api/admin/races").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content(BACKYARD_TEST_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/admin/races/1"))
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andExpect(content().json("{\"id\":1,\"name\":\"Backyard Test\",\"raceDate\":\"2026-10-03\","
                + "\"status\":\"SETUP\",\"startedAt\":null,\"loopDistance\":6706,\"loopDuration\":3600,"
                + "\"loopElevation\":50,\"registrationOpen\":true}", JsonCompareMode.STRICT));

        verify(raceService).create(command);
    }

    @Test
    @DisplayName("CA12 - liste admin et publique : 200 et 2 elements dans les deux cas")
    void ca12_listRaces() throws Exception {
        when(raceService.list()).thenReturn(List.of(backyardTest(RaceStatus.SETUP),
            namedRace(2L, "Autre", LocalDate.of(2026, 11, 1), 5000, 3600, 0, RaceStatus.SETUP, null)));

        mvc.perform(get("/api/admin/races").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)));
        mvc.perform(get("/api/public/races"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(2)))
            .andExpect(jsonPath("$[0].name").value("Backyard Test"))
            .andExpect(jsonPath("$[1].name").value("Autre"));
    }

    @Test
    @DisplayName("CA13 - detail public d'une course RUNNING : status RUNNING, startedAt ISO UTC, registrationOpen false")
    void ca13_publicRaceDetail() throws Exception {
        when(raceService.get(1L)).thenReturn(backyardTest(RaceStatus.RUNNING));

        mvc.perform(get("/api/public/races/1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.startedAt").value("2026-10-03T08:00:00Z"))
            .andExpect(jsonPath("$.registrationOpen").value(false));
    }

    @Test
    @DisplayName("CA14 - PUT /api/admin/races/1 : 200 avec la course modifiee ; service appele avec la commande complete")
    void ca14_updateRace() throws Exception {
        RaceCommand command = new RaceCommand("Backyard 2026", LocalDate.of(2026, 10, 4), 5000, 3000, 30);
        when(raceService.update(1L, command)).thenReturn(
            namedRace(1L, "Backyard 2026", LocalDate.of(2026, 10, 4), 5000, 3000, 30, RaceStatus.SETUP, null));

        mvc.perform(put("/api/admin/races/1").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Backyard 2026\",\"raceDate\":\"2026-10-04\",\"loopDistance\":5000,"
                    + "\"loopDuration\":3000,\"loopElevation\":30}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.name").value("Backyard 2026"))
            .andExpect(jsonPath("$.loopDuration").value(3000))
            .andExpect(jsonPath("$.raceDate").value("2026-10-04"));

        verify(raceService).update(1L, command);
    }

    @Test
    @DisplayName("CA17 - DELETE /api/admin/races/1 : 204 sans corps")
    void ca17_deleteRace() throws Exception {
        mvc.perform(delete("/api/admin/races/1").header("Authorization", ADMIN))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(raceService).delete(1L);
    }

    @Test
    @DisplayName("CA19 - POST /api/admin/races/1/start : 200, RUNNING, startedAt 2026-10-03T08:00:00Z, registrationOpen false")
    void ca19_startRace() throws Exception {
        Race started = backyardTest(RaceStatus.RUNNING);
        when(raceService.start(1L)).thenReturn(started);

        mvc.perform(post("/api/admin/races/1/start").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("RUNNING"))
            .andExpect(jsonPath("$.startedAt").value(T0.toString()))
            .andExpect(jsonPath("$.startedAt").value("2026-10-03T08:00:00Z"))
            .andExpect(jsonPath("$.registrationOpen").value(false));

        verify(raceService).start(1L);
    }
}
