package fr.backyard.api;

import fr.backyard.config.SecurityConfig;
import fr.backyard.service.SessionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 4 - CA1 [back-slice] : E19 {@code GET /api/scan/me} avec la sécurité réelle, le service réel
 * et une Clock fixe. Les autres controllers ne sont pas chargés.
 */
@Tag("INC-4")
@WebMvcTest(controllers = SessionController.class)
@Import({SecurityConfig.class, SessionService.class, SessionApiSliceTest.FixedClockConfig.class})
@ActiveProfiles("test")
class SessionApiSliceTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:20:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    @DisplayName("CA1 - compte SCANNER : 200 {username, role SCANNER, serverTime}, sans Set-Cookie")
    void ca1_scannerSession() throws Exception {
        mvc.perform(get("/api/scan/me").header("Authorization", ApiSliceTest.SCANNER))
            .andExpect(status().isOk())
            .andExpect(content().json(
                "{\"username\":\"scanner-test\",\"role\":\"SCANNER\",\"serverTime\":\"2026-10-03T10:20:00Z\"}",
                JsonCompareMode.STRICT))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA1 - compte ADMIN : 200, role ADMIN, username admin-test")
    void ca1_adminSession() throws Exception {
        mvc.perform(get("/api/scan/me").header("Authorization", ApiSliceTest.ADMIN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.role").value("ADMIN"))
            .andExpect(jsonPath("$.username").value("admin-test"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    @DisplayName("CA1 - sans Authorization : 401 UNAUTHENTICATED sans WWW-Authenticate ; identifiants faux : 401")
    void ca1_unauthenticated() throws Exception {
        mvc.perform(get("/api/scan/me"))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(header().doesNotExist("Set-Cookie"));
        mvc.perform(get("/api/scan/me").header("Authorization", ApiSliceTest.WRONG_PASSWORD))
            .andExpect(status().isUnauthorized())
            .andExpect(header().doesNotExist("WWW-Authenticate"))
            .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
