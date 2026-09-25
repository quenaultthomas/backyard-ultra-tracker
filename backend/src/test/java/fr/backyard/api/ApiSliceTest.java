package fr.backyard.api;

import fr.backyard.config.SecurityConfig;
import fr.backyard.service.ManualDnfService;
import fr.backyard.service.PassageRecordingService;
import fr.backyard.service.RaceBoardService;
import fr.backyard.service.RaceService;
import fr.backyard.service.ReintegrationService;
import fr.backyard.service.RunnerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base des tests de slice de l'API (spec increment 3, section 2 "Tests") : tous les controllers,
 * le gestionnaire d'erreurs, la configuration de securite reelle importee, le profil "test" pour les
 * comptes de test, et les services mockes. Aucune base de donnees.
 */
@WebMvcTest
@Import(SecurityConfig.class)
@ActiveProfiles("test")
abstract class ApiSliceTest {

    /** admin-test / admin-secret (spec increment 3, CA46 a CA55). */
    static final String ADMIN = "Basic YWRtaW4tdGVzdDphZG1pbi1zZWNyZXQ=";
    /** scanner-test / scanner-secret. */
    static final String SCANNER = "Basic c2Nhbm5lci10ZXN0OnNjYW5uZXItc2VjcmV0";
    /** admin-test / mauvais. */
    static final String WRONG_PASSWORD = "Basic YWRtaW4tdGVzdDptYXV2YWlz";
    /** inconnu / admin-secret. */
    static final String UNKNOWN_USER = "Basic aW5jb25udTphZG1pbi1zZWNyZXQ=";

    static final String TOKEN = "3f2c9a4e-8b1d-4c7e-9f00-1a2b3c4d5e6f";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    RaceService raceService;
    @MockitoBean
    RunnerService runnerService;
    @MockitoBean
    RaceBoardService raceBoardService;
    @MockitoBean
    PassageRecordingService passageRecordingService;
    @MockitoBean
    ManualDnfService manualDnfService;
    @MockitoBean
    ReintegrationService reintegrationService;

    /** Corps d'erreur RFC 9457 de RG5 avec statut, code et instance attendus. */
    static ResultMatcher[] problem(int httpStatus, String code, String instance) {
        return new ResultMatcher[] {
            status().is(httpStatus),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.type").value("about:blank"),
            jsonPath("$.title").isNotEmpty(),
            jsonPath("$.status").value(httpStatus),
            jsonPath("$.code").value(code),
            jsonPath("$.detail").isNotEmpty(),
            jsonPath("$.instance").value(instance)
        };
    }

    /** Corps d'erreur avec statut et code, sans contrainte sur l'instance. */
    static ResultMatcher[] problem(int httpStatus, String code) {
        return new ResultMatcher[] {
            status().is(httpStatus),
            content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON),
            jsonPath("$.status").value(httpStatus),
            jsonPath("$.code").value(code)
        };
    }
}
