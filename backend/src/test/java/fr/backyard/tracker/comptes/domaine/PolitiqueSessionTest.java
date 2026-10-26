package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class PolitiqueSessionTest {

    private static final Duration DEUX_HEURES = Duration.ofSeconds(7200);
    private static final Duration DOUZE_HEURES = Duration.ofSeconds(43200);
    private static final Duration SEPT_JOURS = Duration.ofSeconds(604_800);

    // ---------- CA1 ----------

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"ADMIN_MASTER", "ADMIN"})
    @DisplayName("CA1 - les rôles privilégiés reçoivent le délai d'inactivité des rôles privilégiés")
    void doit_donner_le_delai_des_roles_privilegies_a_un_admin_et_a_un_admin_master(Role role) {
        PolitiqueSession politique = new PolitiqueSession(DEUX_HEURES, DOUZE_HEURES);

        assertThat(politique.delaiInactivite(role)).isEqualTo(Duration.ofSeconds(7200));
    }

    @ParameterizedTest
    @EnumSource(value = Role.class, names = {"BENEVOLE", "COUREUR"})
    @DisplayName("CA1 - un bénévole et un coureur reçoivent le délai des autres rôles")
    void doit_donner_le_delai_des_autres_roles_a_un_benevole_et_a_un_coureur(Role role) {
        PolitiqueSession politique = new PolitiqueSession(DEUX_HEURES, DOUZE_HEURES);

        assertThat(politique.delaiInactivite(role)).isEqualTo(Duration.ofSeconds(43200));
    }

    @Test
    @DisplayName("CA1 - avec (3 s, 3600 s), ADMIN reçoit 3 s et COUREUR 3600 s : le délai suit le rôle, pas l'ordre")
    void doit_appliquer_le_delai_court_a_l_admin_et_le_delai_long_au_coureur() {
        PolitiqueSession politique = new PolitiqueSession(Duration.ofSeconds(3), Duration.ofSeconds(3600));

        assertThat(politique.delaiInactivite(Role.ADMIN)).isEqualTo(Duration.ofSeconds(3));
        assertThat(politique.delaiInactivite(Role.COUREUR)).isEqualTo(Duration.ofSeconds(3600));
    }

    @ParameterizedTest
    @EnumSource(Role.class)
    @DisplayName("CA1 - chaque rôle existant obtient un délai strictement positif sans exception")
    void doit_fournir_un_delai_pour_chaque_role_existant(Role role) {
        PolitiqueSession politique = PolitiqueSession.parDefaut();

        assertThat(politique.delaiInactivite(role)).isPositive();
    }

    @Test
    @DisplayName("CA1 - l'énumération complète des rôles est couverte sans exception")
    void doit_couvrir_tous_les_roles_de_l_enumeration() {
        PolitiqueSession politique = PolitiqueSession.parDefaut();

        assertThatCode(() -> Arrays.stream(Role.values()).forEach(politique::delaiInactivite))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - la politique par défaut vaut 7200 s pour les rôles privilégiés et 43200 s pour les autres")
    void doit_proposer_une_politique_par_defaut_de_2_heures_et_12_heures() {
        PolitiqueSession politique = PolitiqueSession.parDefaut();

        assertThat(politique.inactiviteRolesPrivilegies()).isEqualTo(Duration.ofSeconds(7200));
        assertThat(politique.inactiviteAutresRoles()).isEqualTo(Duration.ofSeconds(43200));
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - une durée nulle pour les rôles privilégiés est refusée, le message nomme le paramètre")
    void doit_refuser_une_duree_nulle_pour_les_roles_privilegies() {
        assertThatThrownBy(() -> new PolitiqueSession(null, DOUZE_HEURES))
                .isInstanceOf(PolitiqueSessionInvalideException.class)
                .hasMessageContaining("inactiviteRolesPrivilegies");
    }

    @Test
    @DisplayName("CA2 - une durée nulle pour les autres rôles est refusée, le message nomme le paramètre")
    void doit_refuser_une_duree_nulle_pour_les_autres_roles() {
        assertThatThrownBy(() -> new PolitiqueSession(DEUX_HEURES, null))
                .isInstanceOf(PolitiqueSessionInvalideException.class)
                .hasMessageContaining("inactiviteAutresRoles");
    }

    @ParameterizedTest
    @MethodSource("dureesInvalides")
    @DisplayName("CA2 - une durée nulle, négative, inférieure à 1 s, fractionnaire ou supérieure à 7 jours est refusée pour les rôles privilégiés")
    void doit_refuser_une_duree_invalide_pour_les_roles_privilegies(Duration invalide) {
        assertThatThrownBy(() -> new PolitiqueSession(invalide, SEPT_JOURS))
                .isInstanceOf(PolitiqueSessionInvalideException.class)
                .hasMessageContaining("inactiviteRolesPrivilegies");
    }

    @ParameterizedTest
    @MethodSource("dureesInvalides")
    @DisplayName("CA2 - une durée nulle, négative, inférieure à 1 s, fractionnaire ou supérieure à 7 jours est refusée pour les autres rôles")
    void doit_refuser_une_duree_invalide_pour_les_autres_roles(Duration invalide) {
        assertThatThrownBy(() -> new PolitiqueSession(Duration.ofSeconds(1), invalide))
                .isInstanceOf(PolitiqueSessionInvalideException.class)
                .hasMessageContaining("inactiviteAutresRoles");
    }

    static Stream<Arguments> dureesInvalides() {
        return Stream.of(
                Arguments.of(Duration.ZERO),
                Arguments.of(Duration.ofSeconds(-5)),
                Arguments.of(Duration.ofMillis(500)),
                Arguments.of(Duration.ofMillis(1500)),
                Arguments.of(Duration.ofSeconds(604_801)));
    }

    @Test
    @DisplayName("CA2 - des rôles privilégiés (7200 s) plus longs que les autres (3600 s) sont refusés")
    void doit_refuser_des_roles_privilegies_plus_longs_que_les_autres_roles() {
        assertThatThrownBy(() -> new PolitiqueSession(Duration.ofSeconds(7200), Duration.ofSeconds(3600)))
                .isInstanceOf(PolitiqueSessionInvalideException.class)
                .hasMessageContaining("inactiviteRolesPrivilegies");
    }

    @Test
    @DisplayName("CA2 - un rôle privilégié d'une seconde de plus que les autres est refusé (borne exacte)")
    void doit_refuser_une_seconde_d_ecart_en_faveur_des_roles_privilegies() {
        assertThatThrownBy(() -> new PolitiqueSession(Duration.ofSeconds(3601), Duration.ofSeconds(3600)))
                .isInstanceOf(PolitiqueSessionInvalideException.class);
    }

    @ParameterizedTest
    @MethodSource("politiquesValides")
    @DisplayName("CA2 - les bornes (1 s, 1 s), (7 jours, 7 jours) et l'égalité (3600 s, 3600 s) sont acceptées")
    void doit_accepter_les_bornes_et_l_egalite(long privilegies, long autres) {
        PolitiqueSession politique =
                new PolitiqueSession(Duration.ofSeconds(privilegies), Duration.ofSeconds(autres));

        assertThat(politique.inactiviteRolesPrivilegies()).isEqualTo(Duration.ofSeconds(privilegies));
        assertThat(politique.inactiviteAutresRoles()).isEqualTo(Duration.ofSeconds(autres));
    }

    static Stream<Arguments> politiquesValides() {
        return Stream.of(
                Arguments.of(1L, 1L),
                Arguments.of(604_800L, 604_800L),
                Arguments.of(3600L, 3600L));
    }
}
