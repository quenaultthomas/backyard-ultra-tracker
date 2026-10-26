package fr.backyard.tracker.comptes.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.domaine.PolitiqueSession;
import fr.backyard.tracker.comptes.domaine.PolitiqueSessionInvalideException;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class SessionConfigurationTest {

    private static final String PROPRIETE_ADMIN = "backyard.session.inactivite-admin-secondes";
    private static final String PROPRIETE_AUTRES = "backyard.session.inactivite-coureur-benevole-secondes";

    private final SessionConfiguration configuration = new SessionConfiguration();

    @Test
    @DisplayName("CA3 - les valeurs par défaut \"7200\" et \"43200\" donnent la politique par défaut")
    void doit_construire_la_politique_par_defaut_a_partir_des_textes_par_defaut() {
        PolitiqueSession politique = configuration.politiqueSession("7200", "43200");

        assertThat(politique).isEqualTo(PolitiqueSession.parDefaut());
    }

    @Test
    @DisplayName("CA3 - les espaces autour des valeurs sont ignorés")
    void doit_ignorer_les_espaces_autour_des_valeurs() {
        PolitiqueSession politique = configuration.politiqueSession(" 60 ", "3600");

        assertThat(politique.inactiviteRolesPrivilegies()).isEqualTo(Duration.ofSeconds(60));
        assertThat(politique.inactiviteAutresRoles()).isEqualTo(Duration.ofSeconds(3600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2h", "", "  ", "0", "-5", "604801", "99999999999"})
    @DisplayName("CA3 - une valeur invalide pour les administrateurs empêche le démarrage et cite la propriété")
    void doit_refuser_une_valeur_invalide_pour_les_administrateurs(String valeur) {
        assertThatThrownBy(() -> configuration.politiqueSession(valeur, "43200"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_ADMIN)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc", "1.5", "2h", "", "  ", "0", "-5", "604801", "99999999999"})
    @DisplayName("CA3 - une valeur invalide pour les coureurs et bénévoles empêche le démarrage et cite la propriété")
    void doit_refuser_une_valeur_invalide_pour_les_coureurs_et_benevoles(String valeur) {
        // administrateurs à 1 s pour ne jamais déclencher la règle d'ordre
        assertThatThrownBy(() -> configuration.politiqueSession("1", valeur))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_AUTRES)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"604801", "99999999999"})
    @DisplayName("CA3 - le message d'une valeur trop grande indique la borne maximale 604800")
    void doit_indiquer_la_borne_maximale_dans_le_message(String valeur) {
        assertThatThrownBy(() -> configuration.politiqueSession(valeur, "604800"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("604800");
    }

    @Test
    @DisplayName("CA3 - administrateurs plus longs que les autres rôles : refus, propriétés citées, cause du domaine conservée")
    void doit_refuser_des_administrateurs_plus_longs_que_les_autres_roles() {
        assertThatThrownBy(() -> configuration.politiqueSession("7200", "3600"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(PROPRIETE_ADMIN)
                .hasMessageContaining(PROPRIETE_AUTRES)
                .hasCauseInstanceOf(PolitiqueSessionInvalideException.class);
    }

    @ParameterizedTest
    @MethodSource("bornesValides")
    @DisplayName("CA3 - les bornes 1 et 604800 ainsi que l'égalité sont acceptées")
    void doit_accepter_les_bornes_valides(String admin, String autres, long attenduAdmin, long attenduAutres) {
        PolitiqueSession politique = configuration.politiqueSession(admin, autres);

        assertThat(politique.inactiviteRolesPrivilegies()).isEqualTo(Duration.ofSeconds(attenduAdmin));
        assertThat(politique.inactiviteAutresRoles()).isEqualTo(Duration.ofSeconds(attenduAutres));
    }

    static Stream<Arguments> bornesValides() {
        return Stream.of(
                Arguments.of("1", "1", 1L, 1L),
                Arguments.of("604800", "604800", 604_800L, 604_800L),
                Arguments.of("3600", "3600", 3600L, 3600L));
    }
}
