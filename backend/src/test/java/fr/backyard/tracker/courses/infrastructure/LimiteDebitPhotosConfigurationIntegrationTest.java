package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** Incrément 10.2, CA12 (RG6) : une valeur invalide de BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE empêche le démarrage. */
class LimiteDebitPhotosConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LimiteDebitPhotosConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("CA12 : la valeur « abc » fait échouer le démarrage avec un message nommant la variable")
    void ca12_valeur_non_numerique_refusee() {
        runner.withPropertyValues("backyard.limite-debit-photos.par-minute=abc").run(contexte -> {
            assertThat(contexte).hasFailed();
            assertThat(contexte.getStartupFailure()).hasStackTraceContaining("BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE")
                    .hasStackTraceContaining("abc");
        });
    }

    @Test
    @DisplayName("CA12 : la valeur « -1 » fait échouer le démarrage avec un message nommant la variable")
    void ca12_valeur_negative_refusee() {
        runner.withPropertyValues("backyard.limite-debit-photos.par-minute=-1").run(contexte -> {
            assertThat(contexte).hasFailed();
            assertThat(contexte.getStartupFailure()).hasStackTraceContaining("BACKYARD_LIMITE_DEBIT_PHOTOS_PAR_MINUTE")
                    .hasStackTraceContaining("-1");
        });
    }

    @Test
    @DisplayName("CA12 : les valeurs 0 et 3000 démarrent")
    void ca12_valeurs_valides_acceptees() {
        for (String valeur : new String[] {"0", "3000"}) {
            runner.withPropertyValues("backyard.limite-debit-photos.par-minute=" + valeur)
                    .run(contexte -> assertThat(contexte).hasNotFailed());
        }
    }
}
