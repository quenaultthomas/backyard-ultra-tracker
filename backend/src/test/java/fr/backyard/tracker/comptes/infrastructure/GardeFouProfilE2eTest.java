package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** CA3 - garde-fou de profil e2e (RG8), environnement simulé, sans contexte Spring. */
class GardeFouProfilE2eTest {

    private static final String MESSAGE_REFUS = "Le profil e2e est interdit en production (horloge pilotable).";
    private static final String DEBUT_AVERTISSEMENT = "Profil e2e actif";

    private JournalApplicatif journal;

    @BeforeEach
    void capturer_le_journal() {
        journal = JournalApplicatif.demarrer();
    }

    @AfterEach
    void liberer_le_journal() {
        journal.fermer();
    }

    private List<String> avertissementsProfil() {
        return journal.evenementsApplicatifs().stream()
                .filter(evenement -> evenement.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message != null && message.startsWith(DEBUT_AVERTISSEMENT))
                .toList();
    }

    @Test
    @DisplayName("CA3 : l'environnement production est refusé avec le message exact")
    void doit_refuser_le_demarrage_en_environnement_production() {
        var environnement = new MockEnvironment().withProperty("backyard.environnement", "production");

        assertThatThrownBy(() -> new GardeFouProfilE2e(environnement).verifier())
                .isInstanceOf(RuntimeException.class)
                .hasMessage(MESSAGE_REFUS);
    }

    @ParameterizedTest(name = "environnement = \"{0}\"")
    @ValueSource(strings = {"Production", " production "})
    @DisplayName("CA3 : la casse et les espaces autour de production sont ignorés, refus sans WARN")
    void doit_refuser_production_quelle_que_soit_la_casse_ou_les_espaces(String valeur) {
        var environnement = new MockEnvironment().withProperty("backyard.environnement", valeur);

        assertThatThrownBy(() -> new GardeFouProfilE2e(environnement).verifier())
                .isInstanceOf(RuntimeException.class)
                .hasMessage(MESSAGE_REFUS);
        assertThat(avertissementsProfil()).isEmpty();
    }

    @Test
    @DisplayName("CA3 : le message de refus ne contient aucune valeur de configuration")
    void doit_ne_reveler_aucune_valeur_de_configuration_dans_le_message() {
        var environnement = new MockEnvironment()
                .withProperty("backyard.environnement", "production")
                .withProperty("spring.datasource.password", "mot-de-passe-secret-42")
                .withProperty("backyard.domaine", "exemple-secret.test");

        assertThatThrownBy(() -> new GardeFouProfilE2e(environnement).verifier())
                .isInstanceOf(RuntimeException.class)
                .message()
                .isEqualTo(MESSAGE_REFUS)
                .doesNotContain("mot-de-passe-secret-42", "exemple-secret.test");
    }

    @Test
    @DisplayName("CA3 : aucun avertissement n'est journalisé quand le démarrage est refusé")
    void doit_ne_pas_avertir_quand_le_demarrage_est_refuse() {
        var environnement = new MockEnvironment().withProperty("backyard.environnement", "production");

        assertThatThrownBy(() -> new GardeFouProfilE2e(environnement).verifier());

        assertThat(avertissementsProfil()).isEmpty();
    }

    @ParameterizedTest(name = "environnement = {0}")
    @ValueSource(strings = {"e2e", "dev"})
    @DisplayName("CA3 : les environnements e2e et dev sont autorisés, avec un seul WARN")
    void doit_autoriser_e2e_et_dev_avec_un_seul_avertissement(String valeur) {
        var environnement = new MockEnvironment().withProperty("backyard.environnement", valeur);

        assertThatCode(() -> new GardeFouProfilE2e(environnement).verifier()).doesNotThrowAnyException();

        assertThat(avertissementsProfil())
                .containsExactly("Profil e2e actif : horloge pilotable, ne jamais utiliser en production.");
    }

    @Test
    @DisplayName("CA3 : la propriété absente est autorisée, avec un seul WARN")
    void doit_autoriser_une_propriete_absente_avec_un_seul_avertissement() {
        var environnement = new MockEnvironment();

        assertThatCode(() -> new GardeFouProfilE2e(environnement).verifier()).doesNotThrowAnyException();

        assertThat(avertissementsProfil())
                .containsExactly("Profil e2e actif : horloge pilotable, ne jamais utiliser en production.");
    }
}
