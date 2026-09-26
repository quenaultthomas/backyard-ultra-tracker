package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

/**
 * Spec increment 4 - CA4 [back-IT] (RG54) : la PWA et l'API sont sur la même origine, aucune configuration
 * CORS n'est ajoutée. Vérifié en contexte complet (une origine étrangère ne doit jamais recevoir
 * {@code Access-Control-Allow-Origin}, que ce soit sur une ressource {@code /api/**} protégée, publique,
 * ou sur un fichier de la PWA).
 */
@Tag("INC-4")
@Tag("INC4-CA4")
class NoCorsIT extends AbstractApiIT {

    private static final String FOREIGN_ORIGIN = "https://malveillant.example";

    @Test
    @DisplayName("CA4 - GET /api/public/races avec Origin étranger : pas d'Access-Control-Allow-Origin")
    void ca4_publicEndpointNeverSendsCorsHeaderToForeignOrigin() throws Exception {
        mvc.perform(get("/api/public/races").header("Origin", FOREIGN_ORIGIN))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("CA4 - OPTIONS /api/admin/races (preflight) avec Origin étranger : pas d'Access-Control-Allow-Origin")
    void ca4_preflightFromForeignOriginIsNotAccepted() throws Exception {
        mvc.perform(options("/api/admin/races")
                .header("Origin", FOREIGN_ORIGIN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "authorization,content-type"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    @Test
    @DisplayName("CA4 - GET / avec Origin étranger : pas d'Access-Control-Allow-Origin")
    void ca4_pwaFileNeverSendsCorsHeaderToForeignOrigin() throws Exception {
        mvc.perform(get("/").header("Origin", FOREIGN_ORIGIN))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
