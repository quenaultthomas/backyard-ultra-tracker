package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.4, CA1 : garde de domaine de la suppression d'un admin (RG1 à RG4).
 * Signature attendue : {@code void Compte.verifierSuppressionAdmin(Collection<Compte> admins)} où {@code admins} est
 * la liste verrouillée des Comptes ADMIN (la cible y figure si elle est ADMIN). Refus : {@link CompteNonGerableException},
 * {@code AdminMasterNonSupprimableException}, {@code DernierAdminActifNonSupprimableException} (distincte de
 * {@link DernierAdminActifException}, qui est final et propre à la désactivation).
 */
class CompteSuppressionAdminTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_MARC = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    private static Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return Compte.reconstituer(id, new Pseudo(pseudo), "empreinte", role, CREE_LE, actif);
    }

    private static Compte nadia(boolean actif) {
        return compte(ID_NADIA, "Nadia", Role.ADMIN, actif);
    }

    private static Compte paul(boolean actif) {
        return compte(ID_PAUL, "Paul", Role.ADMIN, actif);
    }

    @ParameterizedTest(name = "CA1 - un compte {0} n''est pas supprimable comme admin")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "COUREUR"})
    @DisplayName("CA1 - la garde refuse un bénévole ou un coureur comme un compte introuvable")
    void doit_refuser_un_compte_qui_n_est_ni_admin_ni_admin_master(Role role) {
        Compte cible = compte(ID_MARC, "Cible", role, true);

        assertThatThrownBy(() -> cible.verifierSuppressionAdmin(List.of(nadia(true), paul(true))))
                .isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - l'admin master n'est jamais supprimable, même quand la liste des admins est vide")
    void doit_refuser_l_admin_master_meme_sans_aucun_admin() {
        Compte patron = compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> patron.verifierSuppressionAdmin(List.of()))
                .isInstanceOf(AdminMasterNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA1 - l'admin master est refusé aussi quand d'autres admins actifs existent")
    void doit_refuser_l_admin_master_avec_des_admins_actifs() {
        Compte patron = compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> patron.verifierSuppressionAdmin(List.of(nadia(true), paul(true))))
                .isInstanceOf(AdminMasterNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA1 - un admin actif seul dans la liste est le dernier admin actif : refus")
    void doit_refuser_le_dernier_admin_actif_quand_il_est_seul() {
        Compte nadia = nadia(true);

        assertThatThrownBy(() -> nadia.verifierSuppressionAdmin(List.of(nadia)))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA1 - un autre admin inactif ne compte pas : le dernier admin actif reste protégé")
    void doit_refuser_le_dernier_admin_actif_quand_l_autre_admin_est_inactif() {
        Compte nadia = nadia(true);

        assertThatThrownBy(() -> nadia.verifierSuppressionAdmin(List.of(nadia, paul(false))))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA1 - l'admin master présent et actif dans la liste n'est jamais compté comme autre admin actif")
    void doit_ne_pas_compter_l_admin_master_comme_autre_admin_actif() {
        Compte nadia = nadia(true);
        Compte patron = compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> nadia.verifierSuppressionAdmin(List.of(patron, nadia)))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);
    }

    @Test
    @DisplayName("CA1 - un admin actif est supprimable quand un autre admin est actif")
    void doit_accepter_un_admin_actif_quand_un_autre_admin_est_actif() {
        Compte paul = paul(true);

        assertThatCode(() -> paul.verifierSuppressionAdmin(List.of(nadia(true), paul)))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - un admin inactif seul est supprimable : il ne retire aucun admin actif")
    void doit_accepter_un_admin_inactif_seul() {
        Compte paul = paul(false);

        assertThatCode(() -> paul.verifierSuppressionAdmin(List.of(paul))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - un admin inactif est supprimable quand les autres admins sont aussi inactifs")
    void doit_accepter_un_admin_inactif_avec_d_autres_inactifs() {
        Compte paul = paul(false);

        assertThatCode(() -> paul.verifierSuppressionAdmin(List.of(nadia(false), paul))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - un admin inactif est supprimable quand un autre admin est actif")
    void doit_accepter_un_admin_inactif_avec_un_admin_actif() {
        Compte paul = paul(false);

        assertThatCode(() -> paul.verifierSuppressionAdmin(List.of(nadia(true), paul))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA1 - le refus de rôle l'emporte sur les autres : un coureur reçoit le refus de rôle même seul")
    void doit_appliquer_le_refus_de_role_en_premier() {
        Compte alice = compte(ID_MARC, "Alice", Role.COUREUR, true);

        assertThatThrownBy(() -> alice.verifierSuppressionAdmin(List.of()))
                .isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA1 - le refus admin master l'emporte sur le refus du dernier admin (liste vide, aucun admin actif)")
    void doit_appliquer_le_refus_admin_master_avant_le_dernier_admin() {
        Compte patron = compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> patron.verifierSuppressionAdmin(List.of(nadia(false))))
                .isNotInstanceOf(DernierAdminActifNonSupprimableException.class)
                .isInstanceOf(AdminMasterNonSupprimableException.class);
    }
}
