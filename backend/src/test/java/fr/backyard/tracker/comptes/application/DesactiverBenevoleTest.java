package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.LECTURE_SIMPLE;
import static fr.backyard.tracker.comptes.application.DepotComptesVerrouilleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.DESAFFECTATION;
import static fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.FERMETURE_TOUTES_SESSIONS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.DesaffectationEspion;
import fr.backyard.tracker.comptes.application.EspionsDesactivationDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.AutoDesactivationInterditeException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA3 : désactivation d'un bénévole (RG4, RG5, RG7, RG10, RG12).
 * Signature attendue : {@code new DesactiverBenevole(DepotComptes, DesaffectationBenevole, InvalidationAutresSessions)}
 * et {@code executer(UUID appelantId, UUID cibleId)}. Le report de la fermeture des sessions après la validation de la
 * transaction n'est pas vérifiable sans Spring : ces tests vérifient qu'elle vient en dernier et jamais en cas d'échec.
 */
class DesactiverBenevoleTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private final List<String> journal = new ArrayList<>();
    private final DepotComptesVerrouilleDeTest depot = new DepotComptesVerrouilleDeTest(journal);
    private final DesaffectationEspion desaffectation = new DesaffectationEspion(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final DesactiverBenevole desactiver = new DesactiverBenevole(depot, desaffectation, sessions);

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private Compte leo() {
        return compte(ID_LEO, "Léo", Role.BENEVOLE, true);
    }

    private void assertAucunEffet() {
        assertThat(depot.misesAJour).isZero();
        assertThat(desaffectation.appels).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - enchaîne chargement sous verrou, mise à jour, désaffectation puis fermeture des sessions en dernier")
    void doit_enchainer_les_operations_dans_l_ordre_et_fermer_les_sessions_en_dernier() {
        leo();
        desaffectation.nombreRetourne = 2;

        desactiver.executer(ID_NADIA, ID_LEO);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, MISE_A_JOUR, DESAFFECTATION, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - le Compte est chargé sous verrou, jamais par une lecture simple")
    void doit_charger_le_compte_sous_verrou() {
        leo();

        desactiver.executer(ID_NADIA, ID_LEO);

        assertThat(journal).doesNotContain(LECTURE_SIMPLE);
    }

    @Test
    @DisplayName("CA3 - le Compte enregistré est inactif, identité et empreinte inchangées")
    void doit_enregistrer_le_compte_desactive_sans_toucher_a_son_identite() {
        Compte avant = leo();

        desactiver.executer(ID_NADIA, ID_LEO);

        Compte apres = depot.enDepot(ID_LEO);
        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(apres.actif()).isFalse();
        assertThat(apres.id()).isEqualTo(avant.id());
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.empreinteMotDePasse()).isEqualTo(avant.empreinteMotDePasse());
        assertThat(apres.role()).isEqualTo(Role.BENEVOLE);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA3 - la désaffectation est demandée pour Léo seulement")
    void doit_demander_la_desaffectation_du_benevole_cible() {
        leo();

        desactiver.executer(ID_NADIA, ID_LEO);

        assertThat(desaffectation.appels).containsExactly(ID_LEO);
    }

    @Test
    @DisplayName("CA3 - toutes les sessions de Léo sont fermées, jamais seulement les autres, aucune autre session touchée")
    void doit_fermer_toutes_les_sessions_du_benevole_cible_uniquement() {
        leo();
        compte(ID_MARC, "Marc", Role.BENEVOLE, true);

        desactiver.executer(ID_NADIA, ID_LEO);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un bénévole sans affectation est désactivé et ses sessions fermées")
    void doit_desactiver_un_benevole_sans_affectation() {
        leo();
        desaffectation.nombreRetourne = 0;

        desactiver.executer(ID_NADIA, ID_LEO);

        assertThat(depot.enDepot(ID_LEO).actif()).isFalse();
        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
    }

    @Test
    @DisplayName("CA3 - un bénévole déjà inactif : aucune écriture, aucune désaffectation, aucune session fermée")
    void doit_ne_rien_faire_pour_un_benevole_deja_inactif() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);

        desactiver.executer(ID_NADIA, ID_LEO);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_LEO).actif()).isFalse();
    }

    @Test
    @DisplayName("CA3 - une cible de rôle ADMIN est introuvable côté bénévoles, sans aucun effet")
    void doit_refuser_une_cible_admin() {
        compte(ID_LEO, "Nadia2", Role.ADMIN, true);

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_LEO).actif()).isTrue();
    }

    @Test
    @DisplayName("CA3 - une cible de rôle COUREUR est introuvable côté bénévoles, sans aucun effet")
    void doit_refuser_une_cible_coureur() {
        compte(ID_LEO, "Alice", Role.COUREUR, true);

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA3 - une cible de rôle ADMIN_MASTER est introuvable côté bénévoles, sans aucun effet")
    void doit_refuser_une_cible_admin_master() {
        compte(ID_LEO, "Patron", Role.ADMIN_MASTER, true);

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA3 - un identifiant inconnu est introuvable, sans aucun effet")
    void doit_refuser_un_identifiant_inconnu() {
        leo();

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_INCONNU))
                .isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.enDepot(ID_LEO).actif()).isTrue();
    }

    @Test
    @DisplayName("CA3 - défense en profondeur : un appelant ne peut pas se désactiver lui-même")
    void doit_refuser_l_auto_desactivation() {
        leo();

        assertThatThrownBy(() -> desactiver.executer(ID_LEO, ID_LEO))
                .isInstanceOf(AutoDesactivationInterditeException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA3 - une erreur du port de désaffectation se propage et aucune session n'est fermée")
    void doit_ne_fermer_aucune_session_quand_la_desaffectation_echoue() {
        leo();
        desaffectation.panne = new IllegalStateException("panne simulée du port");

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_LEO))
                .isSameAs(desaffectation.panne);

        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - une erreur du dépôt à la mise à jour se propage : ni désaffectation ni session fermée")
    void doit_ne_rien_faire_de_plus_quand_la_mise_a_jour_echoue() {
        leo();
        depot.echecMiseAJour = new IllegalStateException("panne simulée du dépôt");

        assertThatThrownBy(() -> desactiver.executer(ID_NADIA, ID_LEO))
                .isSameAs(depot.echecMiseAJour);

        assertThat(desaffectation.appels).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(depot.enDepot(ID_LEO).actif()).isTrue();
    }
}
