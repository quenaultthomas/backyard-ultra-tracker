package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConnexionBloqueeExceptionTest {

    @Test
    @DisplayName("CA11 - ni le message ni toString() ne contiennent le pseudo ni le mot de passe, le temps restant est une Duration")
    void doit_ne_jamais_exposer_pseudo_ni_mot_de_passe() {
        // Le pseudo « Alice » et le mot de passe ne sont jamais fournis à l'exception : elle ne peut pas les divulguer.
        ConnexionBloqueeException exception = new ConnexionBloqueeException(Duration.ofSeconds(900));

        assertThat(exception.getMessage()).doesNotContain("Alice").doesNotContain("alice")
                .doesNotContain("secret-de-test-123");
        assertThat(exception.toString()).doesNotContain("Alice").doesNotContain("alice")
                .doesNotContain("secret-de-test-123");
        assertThat(exception.tempsRestant()).isEqualTo(Duration.ofSeconds(900));
    }

    @Test
    @DisplayName("CA2 - un temps restant de 0,5 s est exposé comme 1 s (arrondi au supérieur, minimum 1 s)")
    void doit_arrondir_le_temps_restant_a_la_seconde_superieure() {
        assertThat(new ConnexionBloqueeException(Duration.ofMillis(500)).tempsRestant())
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(new ConnexionBloqueeException(Duration.ofMillis(1001)).tempsRestant())
                .isEqualTo(Duration.ofSeconds(2));
        assertThat(new ConnexionBloqueeException(Duration.ofSeconds(30)).tempsRestant())
                .isEqualTo(Duration.ofSeconds(30));
    }
}
