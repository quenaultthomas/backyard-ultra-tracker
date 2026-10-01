package fr.backyard.it;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spec increment 7, CA21 : « {@code GET /nimporte-quoi} reste 401 ». Test additif : {@code PwaStaticResourcesIT}
 * n'asserte que « jamais 200 » pour ce chemin. Serveur embarque reel (comme {@code PwaStaticResourcesIT}), anonyme.
 */
@Tag("INC-7")
@Tag("INC7-CA21")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class UnknownPathUnauthorizedIT {

    @LocalServerPort
    private int port;

    private final RestTemplate restTemplate = nonThrowingRestTemplate();

    private static RestTemplate nonThrowingRestTemplate() {
        RestTemplate template = new RestTemplate();
        template.setErrorHandler(new ResponseErrorHandler() {
            @Override
            public boolean hasError(ClientHttpResponse response) {
                return false;
            }
        });
        return template;
    }

    @Test
    @DisplayName("CA21 - GET /nimporte-quoi, anonyme : 401 exactement, pas index.html, pas de WWW-Authenticate")
    void ca21_unknownPathStaysUnauthorized() throws IOException {
        // given
        String indexHtml = new ClassPathResource("static/index.html").getContentAsString(StandardCharsets.UTF_8);

        // when
        ResponseEntity<String> response = restTemplate.getForEntity("http://localhost:" + port + "/nimporte-quoi",
            String.class);

        // then
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody()).isNotEqualTo(indexHtml);
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }
}
