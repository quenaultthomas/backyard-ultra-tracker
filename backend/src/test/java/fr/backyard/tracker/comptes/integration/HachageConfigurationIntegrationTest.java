package fr.backyard.tracker.comptes.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.infrastructure.EncodeurMotDePasseArgon2;
import fr.backyard.tracker.partage.infrastructure.AdresseCliente;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * CA11 (contrat 7.3 RG3) : {@code backyard.hachage.concurrence-max-par-ip} (HACHAGE_CONCURRENCE_MAX_PAR_IP) doit être un
 * entier supérieur ou égal à 1, sinon le contexte ne démarre pas et le message cite la propriété. Le défaut (1) est
 * lu dans application.yml et vérifié par {@link HachageParAdresseIntegrationTest}.
 */
class HachageConfigurationIntegrationTest {

    private ApplicationContextRunner contexte(String valeurParIp) {
        return new ApplicationContextRunner()
                .withBean(PasswordEncoder.class, () -> mock(PasswordEncoder.class))
                .withBean(AdresseCliente.class, () -> new AdresseCliente(""))
                .withUserConfiguration(EncodeurMotDePasseArgon2.class)
                .withPropertyValues("backyard.hachage.concurrence-max=4",
                        "backyard.hachage.attente-max-millisecondes=1000",
                        "backyard.hachage.concurrence-max-par-ip=" + valeurParIp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "0", "-1"})
    @DisplayName("CA11 : concurrence-max-par-ip invalide (abc, 0, -1) : le contexte échoue avec un message citant la propriété")
    void ca11_valeur_invalide_refusee(String valeur) {
        contexte(valeur).run(ctx -> {
            assertThat(ctx).hasFailed();
            List<String> messages = new ArrayList<>();
            for (Throwable t = ctx.getStartupFailure(); t != null; t = t.getCause()) {
                messages.add(String.valueOf(t.getMessage()));
            }
            assertThat(String.join(" | ", messages)).contains("backyard.hachage.concurrence-max-par-ip");
        });
    }

    @Test
    @DisplayName("CA11 : concurrence-max-par-ip valide (1, 10) : le contexte démarre")
    void ca11_valeurs_valides_acceptees() {
        for (String valeur : List.of("1", "10")) {
            contexte(valeur).run(ctx -> assertThat(ctx).hasNotFailed().hasSingleBean(EncodeurMotDePasse.class));
        }
    }
}
