package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Spec increment 4 - CA6 [back-IT] (RG55) : en-têtes de sécurité portés par Spring Security sur toute réponse
 * HTML de la PWA (fichier statique ou route du front renvoyée vers {@code index.html}), et
 * {@code X-Content-Type-Options} également sur une réponse API.
 */
@Tag("INC-4")
@Tag("INC4-CA6")
class SecurityHeadersIT extends AbstractApiIT {

    @ParameterizedTest
    @ValueSource(strings = {"/", "/courses/1"})
    @DisplayName("CA6 - réponse HTML de la PWA : CSP, Permissions-Policy, Referrer-Policy, X-Content-Type-Options")
    void ca6_pwaHtmlResponsesCarrySecurityHeaders(String path) throws Exception {
        mvc.perform(get(path))
            .andExpect(status().isOk())
            .andExpect(header().string("Content-Security-Policy", containsString("default-src 'self'")))
            .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")))
            .andExpect(header().string("Content-Security-Policy", containsString("script-src 'self'")))
            .andExpect(header().string("Content-Security-Policy", not(containsString("unsafe-eval"))))
            .andExpect(header().string("Permissions-Policy", containsString("camera=(self)")))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("CA6 - GET /api/public/races : X-Content-Type-Options nosniff")
    void ca6_apiResponseCarriesContentTypeOptions() throws Exception {
        mvc.perform(get("/api/public/races"))
            .andExpect(status().isOk())
            .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }
}
