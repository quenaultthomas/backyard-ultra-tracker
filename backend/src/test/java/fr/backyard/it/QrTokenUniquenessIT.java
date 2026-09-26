package fr.backyard.it;

import fr.backyard.it.support.AbstractApiIT;
import fr.backyard.service.QrTokenGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Mode rattrapage INC-3 : CA8 (RG6, RG15, CL10) n'est vérifié, dans {@code ApiErrorsSliceTest}, qu'avec un
 * {@code RunnerService} mocké levant une {@code DataIntegrityViolationException} construite à la main. Ce
 * test force une VRAIE violation de la contrainte {@code uq_runner_qr_token} (deux inscriptions avec un
 * générateur de jeton renvoyant volontairement la même valeur) et vérifie que {@code ApiExceptionHandler}
 * mappe correctement l'exception réellement traduite par Spring Data JPA, à travers la chaîne complète
 * HTTP -> sécurité -> service -> JPA -> H2.
 */
@Tag("INC-3")
@Import(QrTokenUniquenessIT.FixedQrTokenGeneratorConfig.class)
class QrTokenUniquenessIT extends AbstractApiIT {

    private static final String FIXED_TOKEN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedQrTokenGeneratorConfig {
        @Bean
        @Primary
        QrTokenGenerator fixedQrTokenGenerator() {
            return new QrTokenGenerator() {
                @Override
                public String generate() {
                    return FIXED_TOKEN;
                }
            };
        }
    }

    @Test
    @Tag("INC3-CA8")
    @DisplayName("CA8 - une seconde inscription (course differente) recevant le meme qr_token qu'une "
        + "premiere inscription reelle est rejetee en 409 DATA_INTEGRITY, sans exposer le nom de la "
        + "contrainte SQL, et un seul coureur est persiste")
    void secondRegistrationWithColldingQrTokenIsRejectedAsDataIntegrityViolation() throws Exception {
        Long race1Id = createSetupRace("IT QrToken Race 1");
        Long race2Id = createSetupRace("IT QrToken Race 2");
        trackRaceForCleanup(race1Id);
        trackRaceForCleanup(race2Id);

        mvc.perform(post("/api/public/races/" + race1Id + "/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Alice\"}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.qrToken").value(FIXED_TOKEN));

        mvc.perform(post("/api/public/races/" + race2Id + "/registrations")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"Bob\"}"))
            .andExpect(status().isConflict())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.code").value("DATA_INTEGRITY"))
            .andExpect(jsonPath("$.detail").value(not(containsString("uq_runner_qr_token"))))
            .andExpect(jsonPath("$.detail").value(not(containsString("constraint"))));

        assertThat(runnerRepository.findByQrToken(FIXED_TOKEN)).isPresent();
        assertThat(runnerRepository.findByRaceId(race2Id))
            .as("l'inscription rejetee de Bob n'a pas ete persistee").isEmpty();
        assertThat(runnerRepository.findByRaceId(race1Id)).hasSize(1);
    }
}
