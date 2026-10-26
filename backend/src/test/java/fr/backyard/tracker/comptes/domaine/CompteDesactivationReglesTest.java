package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA2 : refus de désactivation portés par le domaine (RG3, RG7).
 * Signature attendue : {@code cible.verifierDesactivable(UUID appelantId, Collection<Compte> admins)}, où
 * {@code admins} est la liste des Comptes de rôle ADMIN relue sous verrou (la cible y figure si elle est ADMIN).
 */
class CompteDesactivationReglesTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID ID_QUELCONQUE = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return Compte.reconstituer(id, new Pseudo(pseudo), "empreinte", role, CREE_LE, actif);
    }

    private static Compte patron() {
        return compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);
    }

    private static Compte nadia() {
        return compte(ID_NADIA, "Nadia", Role.ADMIN, true);
    }

    private static Compte paul(boolean actif) {
        return compte(ID_PAUL, "Paul", Role.ADMIN, actif);
    }

    @Test
    @DisplayName("CA2 - désactiver l'admin master est refusé")
    void doit_refuser_la_desactivation_de_l_admin_master() {
        assertThatThrownBy(() -> patron().verifierDesactivable(ID_QUELCONQUE, List.of(nadia())))
                .isInstanceOf(AdminMasterNonDesactivableException.class);
    }

    @Test
    @DisplayName("CA2 - l'admin master qui se désactive lui-même reçoit le refus admin master, pas l'auto-désactivation")
    void doit_refuser_l_admin_master_meme_quand_l_appelant_est_la_cible() {
        assertThatThrownBy(() -> patron().verifierDesactivable(ID_PATRON, List.of(nadia())))
                .isInstanceOf(AdminMasterNonDesactivableException.class);
    }

    @Test
    @DisplayName("CA2 - le refus admin master passe avant le refus du dernier admin actif")
    void doit_refuser_l_admin_master_avant_le_dernier_admin() {
        assertThatThrownBy(() -> patron().verifierDesactivable(ID_QUELCONQUE, List.of()))
                .isInstanceOf(AdminMasterNonDesactivableException.class);
    }

    @Test
    @DisplayName("CA2 - se désactiver soi-même est refusé")
    void doit_refuser_l_auto_desactivation() {
        assertThatThrownBy(() -> nadia().verifierDesactivable(ID_NADIA, List.of(nadia(), paul(true))))
                .isInstanceOf(AutoDesactivationInterditeException.class);
    }

    @Test
    @DisplayName("CA2 - l'auto-désactivation passe avant le refus du dernier admin actif")
    void doit_refuser_l_auto_desactivation_avant_le_dernier_admin() {
        assertThatThrownBy(() -> nadia().verifierDesactivable(ID_NADIA, List.of(nadia())))
                .isInstanceOf(AutoDesactivationInterditeException.class);
    }

    @Test
    @DisplayName("CA2 - l'auto-désactivation reste refusée même si la cible est déjà inactive (avant l'idempotence)")
    void doit_refuser_l_auto_desactivation_d_une_cible_deja_inactive() {
        assertThatThrownBy(() -> paul(false).verifierDesactivable(ID_PAUL, List.of(nadia(), paul(false))))
                .isInstanceOf(AutoDesactivationInterditeException.class);
    }

    @Test
    @DisplayName("CA2 - le seul admin actif ne peut pas être désactivé : l'admin master n'est pas compté")
    void doit_refuser_la_desactivation_du_dernier_admin_actif_sans_compter_l_admin_master() {
        assertThatThrownBy(() -> nadia().verifierDesactivable(ID_PATRON, List.of(patron(), nadia())))
                .isInstanceOf(DernierAdminActifException.class);
    }

    @Test
    @DisplayName("CA2 - un seul admin actif parmi deux admins dont l'autre est inactif : refus du dernier admin")
    void doit_refuser_quand_l_autre_admin_est_inactif() {
        assertThatThrownBy(() -> nadia().verifierDesactivable(ID_PATRON, List.of(nadia(), paul(false))))
                .isInstanceOf(DernierAdminActifException.class);
    }

    @Test
    @DisplayName("CA2 - avec un autre admin actif, la désactivation d'un admin est acceptée")
    void doit_accepter_la_desactivation_quand_un_autre_admin_est_actif() {
        assertThatCode(() -> paul(true).verifierDesactivable(ID_PATRON, List.of(nadia(), paul(true))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - une cible admin déjà inactive est acceptée sans refus du dernier admin (RG7)")
    void doit_accepter_une_cible_deja_inactive_meme_si_elle_n_a_pas_d_autre_admin_actif() {
        assertThatCode(() -> paul(false).verifierDesactivable(ID_PATRON, List.of(paul(false))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - un bénévole est désactivable sans condition sur les admins")
    void doit_accepter_la_desactivation_d_un_benevole() {
        Compte leo = compte(ID_LEO, "Léo", Role.BENEVOLE, true);

        assertThatCode(() -> leo.verifierDesactivable(ID_NADIA, List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA2 - un Compte coureur n'est pas gérable")
    void doit_refuser_un_compte_coureur() {
        Compte alice = compte(ID_ALICE, "Alice", Role.COUREUR, true);

        assertThatThrownBy(() -> alice.verifierDesactivable(ID_PATRON, List.of(nadia())))
                .isInstanceOf(CompteNonGerableException.class);
    }

    @Test
    @DisplayName("CA2 - le contrôle de rôle d'un coureur passe avant l'auto-désactivation")
    void doit_refuser_un_compte_coureur_avant_l_auto_desactivation() {
        Compte alice = compte(ID_ALICE, "Alice", Role.COUREUR, true);

        assertThatThrownBy(() -> alice.verifierDesactivable(ID_ALICE, List.of()))
                .isInstanceOf(CompteNonGerableException.class);
    }
}
