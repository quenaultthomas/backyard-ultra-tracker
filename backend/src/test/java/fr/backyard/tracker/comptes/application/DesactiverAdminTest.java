package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.CHARGEMENT_ADMINS;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.LECTURE_SIMPLE;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.FERMETURE_TOUTES_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.AdminMasterNonDesactivableException;
import fr.backyard.tracker.comptes.domaine.AutoDesactivationInterditeException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifException;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA4 : désactivation d'un admin (RG3, RG7, RG10, RG11).
 * Signature attendue : {@code new DesactiverAdmin(DepotComptes, InvalidationAutresSessions)} et
 * {@code executer(UUID appelantId, UUID cibleId)}. Ce cas d'usage ne reçoit pas le port de désaffectation.
 */
class DesactiverAdminTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final List<String> journal = new ArrayList<>();
    private final DepotComptesVerrouilleDeTest depot = new DepotComptesVerrouilleDeTest(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final DesactiverAdmin desactiver = new DesactiverAdmin(depot, sessions);

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private void patron() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);
    }

    private void nadia() {
        compte(ID_NADIA, "Nadia", Role.ADMIN, true);
    }

    private void paul(boolean actif) {
        compte(ID_PAUL, "Paul", Role.ADMIN, actif);
    }

    private void assertAucunEffet() {
        assertThat(depot.misesAJour).isZero();
        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA4 - avec deux admins actifs : chargement de tous les ADMIN sous verrou, mise à jour, sessions en dernier")
    void doit_charger_les_admins_sous_verrou_puis_desactiver_et_fermer_les_sessions_en_dernier() {
        patron();
        nadia();
        paul(true);

        desactiver.executer(ID_PATRON, ID_PAUL);

        assertThat(journal).contains(CHARGEMENT_ADMINS).doesNotContain(LECTURE_SIMPLE);
        assertThat(journal.indexOf(CHARGEMENT_ADMINS)).isLessThan(journal.indexOf(MISE_A_JOUR));
        assertThat(journal).endsWith(MISE_A_JOUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA4 - Paul est enregistré inactif, Nadia reste active, seules les sessions de Paul sont fermées")
    void doit_desactiver_la_cible_sans_toucher_aux_autres_admins() {
        patron();
        nadia();
        paul(true);

        desactiver.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(depot.enDepot(ID_PAUL).actif()).isFalse();
        assertThat(depot.enDepot(ID_NADIA).actif()).isTrue();
        assertThat(depot.enDepot(ID_PATRON).actif()).isTrue();
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA4 - Nadia seule admin actif : refus du dernier admin actif, aucun effet")
    void doit_refuser_la_desactivation_du_dernier_admin_actif() {
        patron();
        nadia();

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifException.class);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_NADIA).actif()).isTrue();
    }

    @Test
    @DisplayName("CA4 - Paul inactif et Nadia seule active : désactiver Nadia est refusé")
    void doit_refuser_nadia_quand_paul_est_deja_inactif() {
        patron();
        nadia();
        paul(false);

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA4 - la décision se prend sur l'état relu : après la désactivation de Paul, celle de Nadia est refusée")
    void doit_refuser_la_seconde_desactivation_des_deux_derniers_admins() {
        patron();
        nadia();
        paul(true);
        desactiver.executer(ID_PATRON, ID_PAUL);

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifException.class);

        assertThat(depot.enDepot(ID_NADIA).actif()).isTrue();
        assertThat(depot.misesAJour).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - désactiver l'admin master est refusé, même par lui-même, sans effet")
    void doit_refuser_la_desactivation_de_l_admin_master() {
        patron();
        nadia();

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_PATRON))
                .isInstanceOf(AdminMasterNonDesactivableException.class);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_PATRON).actif()).isTrue();
    }

    @Test
    @DisplayName("CA4 - défense en profondeur : un admin ne peut pas se désactiver lui-même")
    void doit_refuser_l_auto_desactivation_d_un_admin() {
        patron();
        nadia();
        paul(true);

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_NADIA))
                .isInstanceOf(AutoDesactivationInterditeException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA4 - un admin déjà inactif, même sans autre admin actif : aucune écriture ni session fermée")
    void doit_ne_rien_faire_pour_un_admin_deja_inactif() {
        patron();
        paul(false);

        desactiver.executer(ID_PATRON, ID_PAUL);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_PAUL).actif()).isFalse();
    }

    @Test
    @DisplayName("CA4 - un bénévole sur la route admins est introuvable, sans effet")
    void doit_refuser_une_cible_benevole() {
        patron();
        nadia();
        compte(ID_LEO, "Léo", Role.BENEVOLE, true);

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA4 - un coureur sur la route admins est introuvable, sans effet")
    void doit_refuser_une_cible_coureur() {
        patron();
        nadia();
        compte(ID_LEO, "Alice", Role.COUREUR, true);

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA4 - un identifiant inconnu est introuvable, sans effet")
    void doit_refuser_un_identifiant_inconnu() {
        patron();
        nadia();

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_INCONNU))
                .isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA4 - une erreur à la mise à jour se propage : aucune session fermée")
    void doit_ne_fermer_aucune_session_quand_la_mise_a_jour_echoue() {
        patron();
        nadia();
        paul(true);
        depot.echecMiseAJour = new IllegalStateException("panne simulée du dépôt");

        assertThatThrownBy(() -> desactiver.executer(ID_PATRON, ID_PAUL)).isSameAs(depot.echecMiseAJour);

        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(depot.enDepot(ID_PAUL).actif()).isTrue();
    }
}
