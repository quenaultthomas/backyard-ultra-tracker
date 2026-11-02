package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesSuppressionAdminDeTest.CHARGEMENT_ADMINS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.FERMETURE_TOUTES_SESSIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.LECTURE_SIMPLE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.REMISE_A_ZERO_COMPTEUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SUPPRESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesSuppressionAdminDeTest.DepotAdmins;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.RegistreEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.AdminMasterNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.4, CA2 : cas d'usage de suppression d'un admin (RG1 à RG9, RG14).
 * Signatures attendues : {@code new SupprimerAdmin(DepotComptes, InvalidationAutresSessions,
 * RegistreTentativesConnexion, PolitiqueBlocage)} et {@code void executer(UUID appelantId, UUID cibleId)}.
 * Le report de la fermeture des sessions après commit n'est pas vérifiable sans Spring : ces tests vérifient qu'elle
 * vient en dernier et jamais en cas d'échec (sans transaction active, fermeture immédiate comme en 8.1 et 9.3).
 */
class SupprimerAdminTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_PAUL = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final List<String> journal = new ArrayList<>();
    private final DepotAdmins depot = new DepotAdmins(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final RegistreEspion registre = new RegistreEspion(journal);
    private final SupprimerAdmin supprimer = new SupprimerAdmin(depot, sessions, registre, PolitiqueBlocage.parDefaut());

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private Compte patron() {
        return compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true);
    }

    private Compte nadia(boolean actif) {
        return compte(ID_NADIA, "Nadia", Role.ADMIN, actif);
    }

    private Compte paul(boolean actif) {
        return compte(ID_PAUL, "Paul", Role.ADMIN, actif);
    }

    private void assertAucunEffet() {
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(registre.effacements).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(sessions.autresSessions).isEmpty();
        assertThat(journal).doesNotContain(SUPPRESSION, REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA2 - relit tous les ADMIN sous verrou avant toute décision, supprime, remet le compteur à zéro puis ferme les sessions en dernier")
    void doit_enchainer_les_operations_dans_l_ordre_et_fermer_les_sessions_en_dernier() {
        patron();
        nadia(true);
        paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(journal).containsExactly(CHARGEMENT_ADMINS, SUPPRESSION, REMISE_A_ZERO_COMPTEUR,
                FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA2 - une cible présente dans la liste verrouillée n'est pas relue par une lecture simple ni mise à jour")
    void doit_prendre_la_cible_dans_la_liste_verrouillee_sans_lecture_simple() {
        patron();
        nadia(true);
        paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(journal).doesNotContain(LECTURE_SIMPLE, MISE_A_JOUR, CHARGEMENT_COMPTE);
        assertThat(depot.misesAJour).isZero();
    }

    @Test
    @DisplayName("CA2 - Paul est supprimé du dépôt, Nadia est conservée")
    void doit_supprimer_physiquement_la_cible_seulement() {
        patron();
        nadia(true);
        paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
        assertThat(depot.contient(ID_PAUL)).isFalse();
        assertThat(depot.contient(ID_NADIA)).isTrue();
        assertThat(depot.contient(ID_PATRON)).isTrue();
    }

    @Test
    @DisplayName("CA2 - le compteur d'échecs du pseudo normalisé de Paul est remis à zéro")
    void doit_remettre_a_zero_le_compteur_du_pseudo_normalise_de_la_cible() {
        patron();
        nadia(true);
        Compte paul = paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(registre.effacements).containsExactly(paul.pseudoNormalise());
    }

    @Test
    @DisplayName("CA2 - seules les sessions de Paul sont fermées, toutes, et jamais celles des autres comptes")
    void doit_fermer_toutes_les_sessions_de_la_cible_uniquement() {
        patron();
        nadia(true);
        paul(true);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un admin désactivé est supprimé comme un actif, sans mise à jour ni réactivation")
    void doit_supprimer_un_admin_desactive_quand_un_autre_admin_est_actif() {
        patron();
        nadia(true);
        paul(false);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.contient(ID_PAUL)).isFalse();
        assertThat(depot.misesAJour).isZero();
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA2 - un admin désactivé qui est le seul ADMIN est supprimé")
    void doit_supprimer_la_cible_inactive_seule_admin() {
        patron();
        paul(false);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA2 - un admin désactivé est supprimé même quand les autres admins sont aussi désactivés")
    void doit_supprimer_la_cible_inactive_quand_les_autres_admins_sont_inactifs() {
        patron();
        nadia(false);
        paul(false);

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA2 - Nadia seule admin actif : refus dernier admin actif, aucune écriture")
    void doit_refuser_le_dernier_admin_actif_sans_ecriture() {
        patron();
        nadia(true);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);

        assertAucunEffet();
        assertThat(depot.contient(ID_NADIA)).isTrue();
    }

    @Test
    @DisplayName("CA2 - Nadia active et Paul désactivé : Nadia reste protégée, Paul est ensuite supprimable")
    void doit_proteger_nadia_quand_paul_est_desactive_puis_supprimer_paul() {
        patron();
        nadia(true);
        paul(false);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);
        assertAucunEffet();

        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA2 - après la suppression de Paul, Nadia devient le dernier admin actif et est refusée")
    void doit_refuser_nadia_apres_la_suppression_de_paul() {
        patron();
        nadia(true);
        paul(true);
        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_NADIA))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
    }

    @Test
    @DisplayName("CA2 - l'admin master, non listé parmi les ADMIN, est lu sous verrou par identifiant puis refusé sans effet")
    void doit_refuser_l_admin_master_lu_par_identifiant_sous_verrou() {
        patron();
        nadia(true);
        paul(true);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PATRON))
                .isInstanceOf(AdminMasterNonSupprimableException.class);

        assertThat(journal).containsExactly(CHARGEMENT_ADMINS, CHARGEMENT_COMPTE);
        assertAucunEffet();
        assertThat(depot.contient(ID_PATRON)).isTrue();
    }

    @Test
    @DisplayName("CA2 - l'admin master est refusé comme tel même s'il est le seul compte restant")
    void doit_refuser_l_admin_master_avant_la_regle_du_dernier_admin() {
        patron();

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PATRON))
                .isInstanceOf(AdminMasterNonSupprimableException.class);

        assertAucunEffet();
    }

    @ParameterizedTest(name = "CA2 - une cible {0} est introuvable côté admins, sans aucun effet")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "COUREUR"})
    @DisplayName("CA2 - une cible bénévole ou coureur donne une erreur de domaine 404 sans aucun effet")
    void doit_refuser_une_cible_qui_n_est_pas_admin(Role role) {
        patron();
        nadia(true);
        paul(true);
        compte(ID_LEO, "Cible", role, true);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.contient(ID_LEO)).isTrue();
    }

    @Test
    @DisplayName("CA2 - un identifiant inconnu est introuvable, sans aucun effet")
    void doit_refuser_un_compte_inconnu() {
        patron();
        nadia(true);
        paul(true);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_INCONNU)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.contient(ID_PAUL)).isTrue();
    }

    @Test
    @DisplayName("CA2 - un second appel sur un admin déjà supprimé est introuvable (non idempotent, RG10)")
    void doit_refuser_une_seconde_suppression_du_meme_admin() {
        patron();
        nadia(true);
        paul(true);
        supprimer.executer(ID_PATRON, ID_PAUL);

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PAUL)).isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.suppressions).containsExactly(ID_PAUL);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_PAUL);
        assertThat(registre.effacements).hasSize(1);
    }

    @Test
    @DisplayName("CA2 - panne du dépôt à la suppression : aucune session fermée, compteur intact")
    void doit_ne_fermer_aucune_session_quand_la_suppression_echoue() {
        patron();
        nadia(true);
        paul(true);
        depot.echecSuppression = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> supprimer.executer(ID_PATRON, ID_PAUL)).isSameAs(depot.echecSuppression);

        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
        assertThat(journal).doesNotContain(REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA2 - les exceptions du cas d'usage ne contiennent ni pseudo ni identifiant de la cible")
    void doit_ne_jamais_exposer_pseudo_ni_identifiant_dans_les_exceptions() {
        patron();
        nadia(true);
        compte(ID_LEO, "PseudoSecret", Role.BENEVOLE, true);

        List<Throwable> exceptions = List.of(
                capturer(() -> supprimer.executer(ID_PATRON, ID_NADIA)),
                capturer(() -> supprimer.executer(ID_PATRON, ID_PATRON)),
                capturer(() -> supprimer.executer(ID_PATRON, ID_LEO)),
                capturer(() -> supprimer.executer(ID_PATRON, ID_INCONNU)));

        assertThat(exceptions).allSatisfy(exception -> {
            String texte = exception.getMessage() + " " + exception;
            assertThat(texte).doesNotContain("PseudoSecret", "Nadia", "Patron", ID_NADIA.toString(),
                    ID_PATRON.toString(), ID_LEO.toString(), ID_INCONNU.toString());
        });
    }

    private static Throwable capturer(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            return exception;
        }
        throw new AssertionError("Une exception était attendue");
    }
}
