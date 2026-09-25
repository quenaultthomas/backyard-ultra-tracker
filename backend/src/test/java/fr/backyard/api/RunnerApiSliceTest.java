package fr.backyard.api;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

import java.util.List;

import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.runner;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec increment 3 - RG15 a RG19, CA24, CA25, CA28, CA30, CA31 (parties [slice]). */
class RunnerApiSliceTest extends ApiSliceTest {

    private final Race r1 = backyardTest(RaceStatus.SETUP);
    private final Runner alice = runner(12L, r1, 6, "Alice", TOKEN);

    @Test
    @DisplayName("CA24 - inscription publique : 201, Location /api/public/runners/12, corps avec qrToken")
    void ca24_publicRegistration() throws Exception {
        when(runnerService.register(1L, "Alice")).thenReturn(alice);

        mvc.perform(post("/api/public/races/1/registrations")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alice\"}"))
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", "/api/public/runners/12"))
            .andExpect(content().json("{\"runnerId\":12,\"raceId\":1,\"bib\":6,\"name\":\"Alice\","
                + "\"qrToken\":\"" + TOKEN + "\"}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("CA25 - inscription avec un nom blanc : 400 VALIDATION_FAILED sur name, service non appele")
    void ca25_blankNameIsRejected() throws Exception {
        mvc.perform(post("/api/public/races/1/registrations")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
            .andExpectAll(problem(400, "VALIDATION_FAILED", "/api/public/races/1/registrations"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name")));

        verify(runnerService, never()).register(anyLong(), anyString());
    }

    @Test
    @DisplayName("CA28 - GET /api/admin/races/1/runners : 200, 3 elements, chacun avec son qrToken")
    void ca28_adminRunnerList() throws Exception {
        when(runnerService.listByRace(1L)).thenReturn(List.of(
            runner(21L, r1, 1, "Un", "tok-1"), runner(23L, r1, 3, "Trois", "tok-3"), runner(25L, r1, 5, "Cinq", "tok-5")));

        mvc.perform(get("/api/admin/races/1/runners").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$", hasSize(3)))
            .andExpect(jsonPath("$[*].qrToken", everyItem(notNullValue())))
            .andExpect(jsonPath("$[*].qrToken", containsInAnyOrder("tok-1", "tok-3", "tok-5")))
            .andExpect(jsonPath("$[*].bib", containsInAnyOrder(1, 3, 5)));
    }

    @Test
    @DisplayName("CA28 - GET /api/admin/runners/12 : 200, AdminRunnerResponse exact (qrToken, champs DNF null presents)")
    void ca28_adminRunnerDetail() throws Exception {
        when(runnerService.get(12L)).thenReturn(alice);

        mvc.perform(get("/api/admin/runners/12").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(content().json("{\"id\":12,\"raceId\":1,\"bib\":6,\"name\":\"Alice\",\"qrToken\":\""
                + TOKEN + "\",\"status\":\"ACTIVE\",\"dnfReason\":null,\"dnfYard\":null}", JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("CA30 - PUT coureur avec bib 0 et nom vide : 400 VALIDATION_FAILED sur bib et name, service non appele")
    void ca30_invalidRunnerUpdate() throws Exception {
        mvc.perform(put("/api/admin/runners/12").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"bib\":0,\"name\":\"\"}"))
            .andExpectAll(problem(400, "VALIDATION_FAILED", "/api/admin/runners/12"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("bib", "name")));

        verify(runnerService, never()).update(anyLong(), anyInt(), anyString());
    }

    @Test
    @DisplayName("CA30 - PUT coureur avec status et qrToken dans le JSON : 200, service appele avec (12, 7, 'Alice B.') uniquement")
    void ca30_unknownPropertiesAreIgnored() throws Exception {
        Runner updated = runner(12L, r1, 7, "Alice B.", TOKEN);
        when(runnerService.update(12L, 7, "Alice B.")).thenReturn(updated);

        mvc.perform(put("/api/admin/runners/12").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bib\":7,\"name\":\"Alice B.\",\"status\":\"WINNER\",\"qrToken\":\"x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.bib").value(7))
            .andExpect(jsonPath("$.name").value("Alice B."))
            .andExpect(jsonPath("$.status").value("ACTIVE"))
            .andExpect(jsonPath("$.qrToken").value(TOKEN));

        verify(runnerService).update(12L, 7, "Alice B.");
        verifyNoMoreInteractions(runnerService);
    }

    @Test
    @DisplayName("CA31 - DELETE /api/admin/runners/12 : 204")
    void ca31_deleteRunner() throws Exception {
        mvc.perform(delete("/api/admin/runners/12").header("Authorization", ADMIN))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));

        verify(runnerService).delete(12L);
    }
}
