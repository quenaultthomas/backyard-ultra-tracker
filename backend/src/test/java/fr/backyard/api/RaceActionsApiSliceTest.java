package fr.backyard.api;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.service.PassageView;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;

import java.time.Instant;
import java.util.List;
import java.util.OptionalLong;

import static fr.backyard.testsupport.TestData.asDnf;
import static fr.backyard.testsupport.TestData.at;
import static fr.backyard.testsupport.TestData.backyardTest;
import static fr.backyard.testsupport.TestData.manual;
import static fr.backyard.testsupport.TestData.runner;
import static fr.backyard.testsupport.TestData.scan;
import static fr.backyard.testsupport.TestData.withId;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Spec increment 3 - RG20 a RG23, RG25, CA32 a CA39 [slice]. */
class RaceActionsApiSliceTest extends ApiSliceTest {

    private static final String SCAN_BODY = "{\"qrToken\":\"tok-b\",\"scannedAt\":\"2026-10-03T08:45:00Z\"}";
    private static final Instant SCANNED_AT = Instant.parse("2026-10-03T08:45:00Z");

    private final Race r1 = backyardTest(RaceStatus.RUNNING);
    private final Runner alice = runner(12L, r1, 6, "Alice", "tok-b");

    private org.springframework.test.web.servlet.ResultActions postScan(String body) throws Exception {
        return mvc.perform(post("/api/scan/passages").header("Authorization", SCANNER)
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("CA32 - scan nominal : 200, ScanResponse exact, service appele avec ('tok-b', 08:45:00Z)")
    void ca32_nominalScan() throws Exception {
        when(passageRecordingService.recordScan("tok-b", SCANNED_AT))
            .thenReturn(withId(scan(alice, 1, at("08:45:00")), 40L));

        postScan(SCAN_BODY)
            .andExpect(status().isOk())
            .andExpect(content().json("{\"passageId\":40,\"runnerId\":12,\"bib\":6,\"runnerName\":\"Alice\","
                + "\"runnerStatus\":\"ACTIVE\",\"yardNumber\":1,\"source\":\"SCAN\","
                + "\"scannedAt\":\"2026-10-03T08:45:00Z\"}", JsonCompareMode.STRICT));

        verify(passageRecordingService).recordScan("tok-b", SCANNED_AT);
    }

    @Test
    @DisplayName("CA33 - scan renvoye a l'identique : 200 avec le passage existant (passageId 40)")
    void ca33_idempotentScan() throws Exception {
        Passage existing = withId(scan(alice, 1, at("08:45:00")), 40L);
        when(passageRecordingService.recordScan("tok-b", SCANNED_AT)).thenReturn(existing);

        postScan(SCAN_BODY).andExpect(status().isOk()).andExpect(jsonPath("$.passageId").value(40));
        postScan(SCAN_BODY).andExpect(status().isOk()).andExpect(jsonPath("$.passageId").value(40));
    }

    @Test
    @DisplayName("CA33 - scan de reactivation automatique : 200, yardNumber 3, runnerStatus ACTIVE")
    void ca33_reactivationScan() throws Exception {
        Runner reactivated = runner(7L, r1, 3, "Bob", "tok-bob");
        when(passageRecordingService.recordScan("tok-bob", at("10:59:30")))
            .thenReturn(withId(scan(reactivated, 3, at("10:59:30")), 41L));

        postScan("{\"qrToken\":\"tok-bob\",\"scannedAt\":\"2026-10-03T10:59:30Z\"}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.yardNumber").value(3))
            .andExpect(jsonPath("$.runnerStatus").value("ACTIVE"));
    }

    @Test
    @DisplayName("CA34 - qrToken vide : 400 VALIDATION_FAILED sur qrToken, service non appele")
    void ca34_blankQrToken() throws Exception {
        postScan("{\"qrToken\":\"\",\"scannedAt\":\"2026-10-03T08:45:00Z\"}")
            .andExpectAll(problem(400, "VALIDATION_FAILED", "/api/scan/passages"))
            .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("qrToken")));

        verify(passageRecordingService, never()).recordScan(anyString(), any());
    }

    @Test
    @DisplayName("CA34 - scannedAt absent : le service est appele avec null et son InvalidInputException donne 400 INVALID_INPUT")
    void ca34_missingScannedAtIsHandledByService() throws Exception {
        when(passageRecordingService.recordScan("tok-b", null))
            .thenThrow(new InvalidInputException("L'instant du scan (scannedAt) est obligatoire"));

        postScan("{\"qrToken\":\"tok-b\"}")
            .andExpectAll(problem(400, "INVALID_INPUT", "/api/scan/passages"));

        verify(passageRecordingService).recordScan("tok-b", null);
    }

    @Test
    @DisplayName("CA34 - qrToken inconnu : 404 RESOURCE_NOT_FOUND")
    void ca34_unknownQrToken() throws Exception {
        when(passageRecordingService.recordScan("tok-b", SCANNED_AT))
            .thenThrow(new ResourceNotFoundException("Aucun coureur pour le qr_token tok-b"));

        postScan(SCAN_BODY).andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/scan/passages"));
    }

    @Test
    @DisplayName("CA34 - double scan : 409 BUSINESS_CONFLICT")
    void ca34_doubleScan() throws Exception {
        when(passageRecordingService.recordScan("tok-b", SCANNED_AT))
            .thenThrow(new BusinessConflictException("Double scan refusé : le coureur 12 a déjà un passage sur le yard 1"));

        postScan(SCAN_BODY)
            .andExpectAll(problem(409, "BUSINESS_CONFLICT", "/api/scan/passages"))
            .andExpect(jsonPath("$.detail").value("Double scan refusé : le coureur 12 a déjà un passage sur le yard 1"));
    }

    @Test
    @DisplayName("CA35 - DNF manuel : 200, status DNF, dnfReason VOLUNTARY, dnfYard 3 ; service appele avec (7, VOLUNTARY)")
    void ca35_manualDnf() throws Exception {
        Runner bob = asDnf(runner(7L, r1, 3, "Bob", "tok-bob"), DnfReason.VOLUNTARY, 3);
        when(manualDnfService.declareDnf(7L, DnfReason.VOLUNTARY)).thenReturn(bob);

        mvc.perform(post("/api/admin/runners/7/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"VOLUNTARY\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(7))
            .andExpect(jsonPath("$.bib").value(3))
            .andExpect(jsonPath("$.status").value("DNF"))
            .andExpect(jsonPath("$.dnfReason").value("VOLUNTARY"))
            .andExpect(jsonPath("$.dnfYard").value(3));

        verify(manualDnfService).declareDnf(7L, DnfReason.VOLUNTARY);
    }

    @Test
    @DisplayName("CA36 - DNF manuel sans raison ({}) : le service est appele avec (7, null) et renvoie 400 INVALID_INPUT")
    void ca36_manualDnfWithoutReason() throws Exception {
        when(manualDnfService.declareDnf(7L, null))
            .thenThrow(new InvalidInputException("Raison de DNF manuel invalide : null"));

        mvc.perform(post("/api/admin/runners/7/dnf").header("Authorization", ADMIN)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpectAll(problem(400, "INVALID_INPUT", "/api/admin/runners/7/dnf"));

        verify(manualDnfService).declareDnf(7L, null);
    }

    /**
     * CA37 : le controller construit les passages de la reponse via RaceBoardService.describePassages
     * (valeurs derivees par les calculateurs, RG27) ; ce mock renvoie les vues des deux passages recrees.
     */
    @Test
    @DisplayName("CA37 - reintegration : 200, coureur ACTIVE, passages recrees yard 3 et 4 MANUAL, scannedAt et loopTimeMillis null, corrected true")
    void ca37_reintegration() throws Exception {
        Runner bob = runner(7L, r1, 3, "Bob", "tok-bob");
        List<Passage> recreated = List.of(manual(bob, 3), manual(bob, 4));
        when(reintegrationService.reintegrate(7L)).thenReturn(recreated);
        when(runnerService.get(7L)).thenReturn(bob);
        when(raceBoardService.describePassages(eq(7L), eq(recreated))).thenReturn(List.of(
            new PassageView(3, PassageSource.MANUAL, null, OptionalLong.empty(), true),
            new PassageView(4, PassageSource.MANUAL, null, OptionalLong.empty(), true)));

        mvc.perform(post("/api/admin/runners/7/reintegration").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runner.id").value(7))
            .andExpect(jsonPath("$.runner.status").value("ACTIVE"))
            .andExpect(jsonPath("$.runner").value(org.hamcrest.Matchers.hasKey("dnfReason")))
            .andExpect(jsonPath("$.runner.dnfReason").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.runner.qrToken").value("tok-bob"))
            .andExpect(content().json("{\"recreatedPassages\":["
                + "{\"yardNumber\":3,\"source\":\"MANUAL\",\"scannedAt\":null,\"loopTimeMillis\":null,\"corrected\":true},"
                + "{\"yardNumber\":4,\"source\":\"MANUAL\",\"scannedAt\":null,\"loopTimeMillis\":null,\"corrected\":true}"
                + "]}", JsonCompareMode.LENIENT))
            .andExpect(jsonPath("$.recreatedPassages", hasSize(2)))
            .andExpect(jsonPath("$.recreatedPassages[0]").value(
                org.hamcrest.Matchers.hasKey("scannedAt")))
            .andExpect(jsonPath("$.recreatedPassages[0]").value(
                org.hamcrest.Matchers.hasKey("loopTimeMillis")));

        verify(reintegrationService).reintegrate(7L);
        verify(runnerService).get(7L);
    }

    @Test
    @DisplayName("CA38 - reintegration sans passage a recreer : 200, recreatedPassages vide")
    void ca38_reintegrationWithoutPassage() throws Exception {
        Runner bob = runner(7L, r1, 3, "Bob", "tok-bob");
        when(reintegrationService.reintegrate(7L)).thenReturn(List.of());
        when(runnerService.get(7L)).thenReturn(bob);

        mvc.perform(post("/api/admin/runners/7/reintegration").header("Authorization", ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.runner.status").value("ACTIVE"))
            .andExpect(jsonPath("$.recreatedPassages", hasSize(0)));
    }

    @Test
    @DisplayName("CA38 - reintegration refusee (coureur ACTIVE) : 409, RunnerService.get non appele")
    void ca38_reintegrationConflict() throws Exception {
        when(reintegrationService.reintegrate(7L)).thenThrow(new BusinessConflictException(
            "Impossible de réintégrer : le coureur 7 est au statut ACTIVE (attendu DNF)"));

        mvc.perform(post("/api/admin/runners/7/reintegration").header("Authorization", ADMIN))
            .andExpectAll(problem(409, "BUSINESS_CONFLICT", "/api/admin/runners/7/reintegration"));

        verify(runnerService, never()).get(anyLong());
    }

    @Test
    @DisplayName("CA38 - reintegration d'un coureur inconnu : 404, RunnerService.get non appele")
    void ca38_reintegrationNotFound() throws Exception {
        when(reintegrationService.reintegrate(7L)).thenThrow(new ResourceNotFoundException("Coureur introuvable : id 7"));

        mvc.perform(post("/api/admin/runners/7/reintegration").header("Authorization", ADMIN))
            .andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/admin/runners/7/reintegration"));

        verify(runnerService, never()).get(anyLong());
    }

    @Test
    @DisplayName("CA39 - POST /api/admin/races/1/close (ADMIN) : 404 RESOURCE_NOT_FOUND, pas de cloture manuelle exposee")
    void ca39_noManualClosing() throws Exception {
        mvc.perform(post("/api/admin/races/1/close").header("Authorization", ADMIN))
            .andExpectAll(problem(404, "RESOURCE_NOT_FOUND", "/api/admin/races/1/close"));
    }
}
