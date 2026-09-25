package fr.backyard.api;

import fr.backyard.domain.DnfReason;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Spec increment 3 - RG5 a RG8, RG23, CA1 a CA9 [slice]. */
class ApiErrorsSliceTest extends ApiSliceTest {

    @Test
    @DisplayName("CA1 - ResourceNotFoundException : 404 ProblemDetail complet (type, title, status, code, detail, instance)")
    void ca1_notFoundProblemDetail() throws Exception {
        when(raceService.get(99L)).thenThrow(new ResourceNotFoundException("Course introuvable : id 99"));

        mvc.perform(get("/api/admin/races/99").header("Authorization", ADMIN))
            .andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/admin/races/99"))
            .andExpect(jsonPath("$.detail").value("Course introuvable : id 99"));
    }

    @Test
    @DisplayName("CA2 - BusinessConflictException : 409 BUSINESS_CONFLICT, detail egal au message")
    void ca2_conflictProblemDetail() throws Exception {
        String message = "Impossible de déclarer DNF : le coureur 7 est au statut DNF (attendu ACTIVE)";
        when(manualDnfService.declareDnf(7L, DnfReason.VOLUNTARY)).thenThrow(new BusinessConflictException(message));

        mvc.perform(post("/api/admin/runners/7/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"VOLUNTARY\"}"))
            .andExpectAll(problem(409, "BUSINESS_CONFLICT", "/api/admin/runners/7/dnf"))
            .andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    @DisplayName("CA3 - InvalidInputException : 400 INVALID_INPUT, detail contenant TIMEOUT")
    void ca3_invalidInputProblemDetail() throws Exception {
        when(manualDnfService.declareDnf(7L, DnfReason.TIMEOUT)).thenThrow(new InvalidInputException(
            "Raison de DNF manuel invalide : TIMEOUT (TIMEOUT est réservé à l'auto-DNF)"));

        mvc.perform(post("/api/admin/runners/7/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"TIMEOUT\"}"))
            .andExpectAll(problem(400, "INVALID_INPUT", "/api/admin/runners/7/dnf"))
            .andExpect(jsonPath("$.detail").value(containsString("TIMEOUT")));
    }

    @Test
    @DisplayName("CA4 - Bean Validation : 400 VALIDATION_FAILED, errors sur name, loopDistance, loopElevation exactement ; service non appele")
    void ca4_beanValidation() throws Exception {
        mvc.perform(post("/api/admin/races").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"\",\"raceDate\":\"2026-10-03\",\"loopDistance\":0,\"loopDuration\":3600,"
                    + "\"loopElevation\":-1}"))
            .andExpectAll(problem(400, "VALIDATION_FAILED", "/api/admin/races"))
            .andExpect(jsonPath("$.errors", hasSize(3)))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("name", "loopDistance", "loopElevation")))
            .andExpect(jsonPath("$.errors[0].message").isNotEmpty());

        verify(raceService, never()).create(any());
    }

    @Test
    @DisplayName("CA5 - identifiant non numerique (GET /api/admin/races/abc) : 400 MALFORMED_REQUEST, aucun service appele")
    void ca5_nonNumericId() throws Exception {
        mvc.perform(get("/api/admin/races/abc").header("Authorization", ADMIN))
            .andExpectAll(problem(400, "MALFORMED_REQUEST", "/api/admin/races/abc"));

        verifyNoServiceCalled();
    }

    @Test
    @DisplayName("CA5 - corps JSON mal forme ('{') : 400 MALFORMED_REQUEST, aucun service appele")
    void ca5_malformedJson() throws Exception {
        mvc.perform(post("/api/admin/races").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{"))
            .andExpectAll(problem(400, "MALFORMED_REQUEST", "/api/admin/races"));

        verifyNoServiceCalled();
    }

    @Test
    @DisplayName("CA5 - enum inconnu ('ABANDON') : 400 MALFORMED_REQUEST, aucun service appele")
    void ca5_unknownEnum() throws Exception {
        mvc.perform(post("/api/admin/runners/7/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"ABANDON\"}"))
            .andExpectAll(problem(400, "MALFORMED_REQUEST", "/api/admin/runners/7/dnf"));

        verifyNoServiceCalled();
    }

    @Test
    @DisplayName("CA5 / CL2 - corps absent : 400 MALFORMED_REQUEST, aucun service appele")
    void ca5_missingBody() throws Exception {
        mvc.perform(post("/api/admin/races").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON))
            .andExpectAll(problem(400, "MALFORMED_REQUEST", "/api/admin/races"));

        verifyNoServiceCalled();
    }

    @Test
    @DisplayName("CA6 - IllegalStateException du domaine : 500 INTERNAL_INCONSISTENCY, detail egal au message")
    void ca6_illegalStateIsInternalInconsistency() throws Exception {
        String message = "Donnée incohérente : course RUNNING sans started_at (course 1)";
        when(raceBoardService.board(1L)).thenThrow(new IllegalStateException(message));

        mvc.perform(get("/api/public/races/1/board"))
            .andExpectAll(problem(500, "INTERNAL_INCONSISTENCY", "/api/public/races/1/board"))
            .andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    @DisplayName("CA6 - IllegalArgumentException du domaine : 500 INTERNAL_INCONSISTENCY")
    void ca6_illegalArgumentIsInternalInconsistency() throws Exception {
        String message = "Numéro de yard invalide : 0 (minimum 1)";
        when(raceBoardService.board(1L)).thenThrow(new IllegalArgumentException(message));

        mvc.perform(get("/api/public/races/1/board"))
            .andExpectAll(problem(500, "INTERNAL_INCONSISTENCY", "/api/public/races/1/board"))
            .andExpect(jsonPath("$.detail").value(message));
    }

    @Test
    @DisplayName("CA7 - exception inattendue : 500 INTERNAL_ERROR, detail sans le message d'origine")
    void ca7_unexpectedException() throws Exception {
        when(raceService.list()).thenThrow(new RuntimeException("boom"));

        mvc.perform(get("/api/admin/races").header("Authorization", ADMIN))
            .andExpectAll(problem(500, "INTERNAL_ERROR", "/api/admin/races"))
            .andExpect(jsonPath("$.detail").value(not(containsString("boom"))));
    }

    @Test
    @DisplayName("CA8 - DataIntegrityViolationException : 409 DATA_INTEGRITY, detail sans nom de contrainte")
    void ca8_dataIntegrityViolation() throws Exception {
        when(runnerService.register(1L, "Alice")).thenThrow(new DataIntegrityViolationException(
            "could not execute statement; constraint [uq_runner_race_bib]; SQL [insert into runner ...]"));

        mvc.perform(post("/api/public/races/1/registrations")
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Alice\"}"))
            .andExpectAll(problem(409, "DATA_INTEGRITY", "/api/public/races/1/registrations"))
            .andExpect(jsonPath("$.detail").value(not(containsString("uq_runner_race_bib"))))
            .andExpect(jsonPath("$.detail").value(not(containsString("SQL"))));
    }

    @Test
    @DisplayName("CA9 - methode non supportee (PATCH /api/admin/races/1, ADMIN) : 405 METHOD_NOT_ALLOWED")
    void ca9_methodNotAllowed() throws Exception {
        mvc.perform(patch("/api/admin/races/1").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpectAll(problem(405, "METHOD_NOT_ALLOWED", "/api/admin/races/1"));

        verifyNoServiceCalled();
    }

    @Test
    @DisplayName("CA9 - chemin inconnu (GET /api/admin/inconnu, ADMIN) : 404 RESOURCE_NOT_FOUND")
    void ca9_unknownPath() throws Exception {
        mvc.perform(get("/api/admin/inconnu").header("Authorization", ADMIN))
            .andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/admin/inconnu"));
    }

    private void verifyNoServiceCalled() {
        verifyNoInteractions(raceService, runnerService, raceBoardService, passageRecordingService,
            manualDnfService, reintegrationService);
    }
}
