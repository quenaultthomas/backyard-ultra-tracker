package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesChangementRoleDeTest.CONSULTATION_COURSES;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.FERMETURE_TOUTES_SESSIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.MISE_A_JOUR;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesChangementRoleDeTest.ConsultationCoursesEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.DepotAvecSuppression;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
import fr.backyard.tracker.comptes.domaine.CoureurInscritCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.CoureurInscritCourseEnPreparationException;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee.Statut;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Incrément 9.7, CA2 : passage d'un coureur en admin (RG1, RG2, RG4, RG5, RG6, RG8).
 * Signatures attendues :
 * <ul>
 *   <li>{@code new PasserCoureurEnAdmin(DepotComptes, InvalidationAutresSessions, ConsultationInscriptions)}
 *       (mêmes dépendances et même ordre que {@code PasserCoureurEnBenevole}) ;</li>
 *   <li>{@code Compte executer(UUID appelantId, UUID cibleId)} ;</li>
 *   <li>le déroulé interne est libre (RG11) : seuls les effets observables sont figés ici.</li>
 * </ul>
 * Le compteur d'échecs de connexion n'est pas une dépendance du cas d'usage : il n'est jamais touché.
 */
class PasserCoureurEnAdminTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_AUTRE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID COURSE_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COURSE_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID COURSE_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    private final List<String> journal = new ArrayList<>();
    private final DepotAvecSuppression depot = new DepotAvecSuppression(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final ConsultationCoursesEspion consultation = new ConsultationCoursesEspion(journal);
    private final PasserCoureurEnAdmin passer = new PasserCoureurEnAdmin(depot, sessions, consultation);

    @AfterEach
    void nettoyerSynchronisation() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private Compte compte(UUID id, String pseudo, Role role, boolean actif, String empreinte) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), empreinte, role, CREE_LE, actif));
    }

    private Compte alice() {
        return compte(ID_ALICE, "AliceSecrete", Role.COUREUR, true, "empreinte-alice");
    }

    private static CourseNonTerminee enPreparation(UUID id, String nom) {
        return new CourseNonTerminee(id, nom, Statut.EN_PREPARATION);
    }

    private static CourseNonTerminee enCours(UUID id, String nom) {
        return new CourseNonTerminee(id, nom, Statut.EN_COURS);
    }

    private void assertAucuneEcritureNiSession() {
        assertThat(depot.misesAJour).isZero();
        assertThat(depot.suppressions).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un coureur sans Course non terminée devient ADMIN, même identité, mis à jour une fois")
    void doit_passer_un_coureur_sans_inscription_bloquante_en_admin() {
        Compte avant = alice();

        Compte apres = passer.executer(ID_PATRON, ID_ALICE);

        assertThat(apres.role()).isEqualTo(Role.ADMIN);
        assertThat(apres.id()).isEqualTo(ID_ALICE);
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.empreinteMotDePasse()).isEqualTo(avant.empreinteMotDePasse());
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
        assertThat(apres.actif()).isTrue();
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.ADMIN);
        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(depot.suppressions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un coureur dont toutes les Inscriptions sont TERMINEE est promu (aucune Course non terminée)")
    void doit_promouvoir_un_coureur_sans_course_non_terminee() {
        alice();
        // Le port ne renvoie que les Courses non TERMINEE : une Inscription TERMINEE se traduit par une liste vide.

        Compte apres = passer.executer(ID_PATRON, ID_ALICE);

        assertThat(apres.role()).isEqualTo(Role.ADMIN);
        assertThat(consultation.appels).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA2 - l'ordre est : chargement sous verrou, consultation, mise à jour, sessions en dernier")
    void doit_enchainer_chargement_consultation_mise_a_jour_puis_fermeture_des_sessions() {
        alice();

        passer.executer(ID_PATRON, ID_ALICE);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, CONSULTATION_COURSES, MISE_A_JOUR,
                FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA2 - toutes les sessions du seul Compte promu sont fermées, jamais celles des autres")
    void doit_fermer_toutes_les_sessions_du_seul_compte_promu() {
        alice();
        compte(ID_AUTRE, "Bruno", Role.COUREUR, true, "empreinte-bruno");

        passer.executer(ID_PATRON, ID_ALICE);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
        assertThat(sessions.autresSessions).isEmpty();
        assertThat(depot.trouverParId(ID_AUTRE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA2 - les sessions ne sont fermées qu'après la validation de la transaction")
    void doit_fermer_les_sessions_seulement_apres_la_validation_de_la_transaction() {
        alice();
        TransactionSynchronizationManager.initSynchronization();

        passer.executer(ID_PATRON, ID_ALICE);

        assertThat(sessions.toutesLesSessions).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA2 - une transaction annulée ne ferme aucune session")
    void doit_ne_fermer_aucune_session_quand_la_transaction_est_annulee() {
        alice();
        TransactionSynchronizationManager.initSynchronization();

        passer.executer(ID_PATRON, ID_ALICE);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchro -> synchro.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - des Courses EN_PREPARATION seules : refus EN_PREPARATION portant ces Courses")
    void doit_refuser_un_coureur_inscrit_a_des_courses_en_preparation() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "Backyard A"), enPreparation(COURSE_2, "Backyard B"));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnPreparationException.class,
                        refus -> assertThat(refus.courses()).containsExactly(
                                enPreparation(COURSE_1, "Backyard A"), enPreparation(COURSE_2, "Backyard B")));
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA2 - EN_COURS et EN_PREPARATION : refus EN_COURS portant les seules Courses EN_COURS")
    void doit_refuser_en_priorite_pour_une_course_en_cours() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "Backyard préparation"), enCours(COURSE_2, "Backyard du jour"));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).containsExactly(enCours(COURSE_2, "Backyard du jour")));
        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA2 - les Courses du refus sont triées par nom sans tenir compte de la casse")
    void doit_trier_les_courses_du_refus_par_nom_insensible_a_la_casse() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "zèbre"), enPreparation(COURSE_2, "Backyard"),
                enPreparation(COURSE_3, "alpha"));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnPreparationException.class,
                        refus -> assertThat(refus.courses()).extracting(CourseNonTerminee::nom)
                                .containsExactly("alpha", "Backyard", "zèbre"));
    }

    @Test
    @DisplayName("CA2 - à nom égal, les Courses du refus sont triées par identifiant")
    void doit_departager_les_noms_identiques_par_identifiant() {
        alice();
        consultation.inscrireA(enCours(COURSE_3, "Backyard"), enCours(COURSE_1, "BACKYARD"),
                enCours(COURSE_2, "backyard"));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).extracting(CourseNonTerminee::id)
                                .containsExactly(COURSE_1, COURSE_2, COURSE_3));
    }

    @Test
    @DisplayName("CA2 - un Compte inconnu est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_compte_inconnu_sans_consulter_les_inscriptions() {
        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_INCONNU)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @ParameterizedTest(name = "CA2 - un compte {0} est introuvable : aucune consultation, aucune écriture")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA2 - un compte qui n'est pas coureur est introuvable")
    void doit_refuser_un_compte_qui_n_est_pas_coureur(Role role) {
        compte(ID_AUTRE, "Cible", role, true, "empreinte");

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_AUTRE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_AUTRE).orElseThrow().role()).isEqualTo(role);
    }

    @Test
    @DisplayName("CA2 - l'appelant lui-même (admin master) est introuvable comme toute cible non coureur")
    void doit_refuser_le_compte_de_l_appelant() {
        compte(ID_PATRON, "Patron", Role.ADMIN_MASTER, true, "empreinte-patron");

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_PATRON)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA2 - un coureur anonymisé est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_coureur_anonymise() {
        depot.ajouter(alice().anonymiser());

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA2 - un coureur inactif est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_coureur_inactif() {
        compte(ID_ALICE, "AliceSecrete", Role.COUREUR, false, "empreinte-alice");

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA2 - un second passage sur le même Compte est introuvable (non idempotent)")
    void doit_refuser_un_second_passage_du_meme_compte() {
        alice();
        passer.executer(ID_PATRON, ID_ALICE);

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA2 - panne de la consultation : aucune écriture ni session fermée")
    void doit_ne_rien_ecrire_quand_la_consultation_echoue() {
        alice();
        consultation.panne = new IllegalStateException("panne de la consultation");

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE)).isSameAs(consultation.panne);

        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA2 - panne de mettreAJour : aucune session fermée")
    void doit_ne_fermer_aucune_session_quand_la_mise_a_jour_echoue() {
        DepotAvecSuppression depotEnPanne = new DepotAvecSuppression(journal) {
            @Override
            public void mettreAJour(Compte compte) {
                throw new IllegalStateException("panne du dépôt");
            }
        };
        depotEnPanne.ajouter(Compte.reconstituer(ID_ALICE, new Pseudo("AliceSecrete"), "e", Role.COUREUR, CREE_LE, true));
        PasserCoureurEnAdmin cas = new PasserCoureurEnAdmin(depotEnPanne, sessions, consultation);

        assertThatThrownBy(() -> cas.executer(ID_PATRON, ID_ALICE)).isInstanceOf(IllegalStateException.class);

        assertThat(sessions.toutesLesSessions).isEmpty();
    }
}
