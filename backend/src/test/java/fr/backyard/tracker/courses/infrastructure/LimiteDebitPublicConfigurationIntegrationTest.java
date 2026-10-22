package fr.backyard.tracker.courses.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

/** Incrément 7.2, CA10 : une valeur invalide de BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE empêche le démarrage. */
class LimiteDebitPublicConfigurationIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LimiteDebitPublicConfiguration.class)
            .withBean(Clock.class, Clock::systemUTC)
            .withBean(JsonMapper.class, () -> JsonMapper.builder().build());

    @Test
    @DisplayName("CA10 : la valeur « abc » fait échouer le démarrage avec un message explicite")
    void ca10_valeur_non_numerique_refusee() {
        runner.withPropertyValues("backyard.limite-debit-public.par-minute=abc").run(contexte -> {
            assertThat(contexte).hasFailed();
            assertThat(contexte.getStartupFailure()).hasStackTraceContaining("BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE")
                    .hasStackTraceContaining("abc");
        });
    }

    @Test
    @DisplayName("CA10 : la valeur « -1 » fait échouer le démarrage avec un message explicite")
    void ca10_valeur_negative_refusee() {
        runner.withPropertyValues("backyard.limite-debit-public.par-minute=-1").run(contexte -> {
            assertThat(contexte).hasFailed();
            assertThat(contexte.getStartupFailure()).hasStackTraceContaining("BACKYARD_LIMITE_DEBIT_PUBLIC_PAR_MINUTE")
                    .hasStackTraceContaining("-1");
        });
    }

    @Test
    @DisplayName("CA10 : les valeurs 0 et 1200 démarrent")
    void ca10_valeurs_valides_acceptees() {
        for (String valeur : new String[] {"0", "1200"}) {
            runner.withPropertyValues("backyard.limite-debit-public.par-minute=" + valeur)
                    .run(contexte -> assertThat(contexte).hasNotFailed());
        }
    }
}
