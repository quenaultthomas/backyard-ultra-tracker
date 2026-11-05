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
 * Incrément 9.6b, CA13 : passage d'un coureur en bénévole (RG11 à RG15, RG17).
 * Signatures attendues (noms indicatifs de la spec ou choisis par le testeur) :
 * <ul>
 *   <li>{@code new PasserCoureurEnBenevole(DepotComptes, InvalidationAutresSessions, ConsultationInscriptions)} ;</li>
 *   <li>{@code Compte executer(UUID appelantId, UUID cibleId)} ;</li>
 *   <li>port {@code ConsultationInscriptions.coursesNonTerminees(UUID compteId)} renvoyant
 *       {@code List<CourseNonTerminee>} ; record {@code CourseNonTerminee(UUID id, String nom, Statut statut)} avec
 *       enum imbriqué {@code Statut {EN_PREPARATION, EN_COURS}} ;</li>
 *   <li>exceptions {@code CoureurInscritCourseEnCoursException} et {@code CoureurInscritCourseEnPreparationException}
 *       (constructeur {@code (List<CourseNonTerminee>)}, accesseur {@code courses()} trié par nom insensible à la casse
 *       puis identifiant).</li>
 * </ul>
 * Le compteur d'échecs de connexion n'est pas une dépendance du cas d'usage : il n'est donc jamais touché (RG15).
 */
class PasserCoureurEnBenevoleTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_AUTRE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID COURSE_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID COURSE_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID COURSE_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final String PSEUDO_ALICE = "AliceSecrete";

    private final List<String> journal = new ArrayList<>();
    private final DepotAvecSuppression depot = new DepotAvecSuppression(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final ConsultationCoursesEspion consultation = new ConsultationCoursesEspion(journal);
    private final PasserCoureurEnBenevole passer = new PasserCoureurEnBenevole(depot, sessions, consultation);

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
        return compte(ID_ALICE, PSEUDO_ALICE, Role.COUREUR, true, "empreinte-alice");
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
    @DisplayName("CA13 - un coureur sans Course non terminée devient BENEVOLE, même identité, sans suppression")
    void doit_passer_un_coureur_sans_inscription_bloquante_en_benevole() {
        Compte avant = alice();

        Compte apres = passer.executer(ID_NADIA, ID_ALICE);

        assertThat(apres.role()).isEqualTo(Role.BENEVOLE);
        assertThat(apres.id()).isEqualTo(ID_ALICE);
        assertThat(apres.pseudo()).isEqualTo(avant.pseudo());
        assertThat(apres.empreinteMotDePasse()).isEqualTo(avant.empreinteMotDePasse());
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.BENEVOLE);
        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(depot.suppressions).isEmpty();
    }

    @Test
    @DisplayName("CA13 - l'ordre est : chargement sous verrou, consultation des Inscriptions, mise à jour, sessions en dernier")
    void doit_enchainer_chargement_consultation_mise_a_jour_puis_fermeture_des_sessions() {
        alice();

        passer.executer(ID_NADIA, ID_ALICE);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, CONSULTATION_COURSES, MISE_A_JOUR,
                FERMETURE_TOUTES_SESSIONS);
        assertThat(consultation.appels).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA13 - toutes les sessions du Compte promu sont fermées, jamais celles des autres Comptes")
    void doit_fermer_toutes_les_sessions_du_seul_compte_promu() {
        alice();
        compte(ID_AUTRE, "Bruno", Role.COUREUR, true, "empreinte-bruno");

        passer.executer(ID_NADIA, ID_ALICE);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
        assertThat(sessions.autresSessions).isEmpty();
        assertThat(depot.trouverParId(ID_AUTRE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA13 - les sessions ne sont fermées qu'après la validation de la transaction")
    void doit_fermer_les_sessions_seulement_apres_la_validation_de_la_transaction() {
        alice();
        TransactionSynchronizationManager.initSynchronization();

        passer.executer(ID_NADIA, ID_ALICE);

        assertThat(sessions.toutesLesSessions).isEmpty();
        TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA13 - une transaction annulée ne ferme aucune session")
    void doit_ne_fermer_aucune_session_quand_la_transaction_est_annulee() {
        alice();
        TransactionSynchronizationManager.initSynchronization();

        passer.executer(ID_NADIA, ID_ALICE);
        TransactionSynchronizationManager.getSynchronizations()
                .forEach(synchro -> synchro.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA13 - des Courses EN_PREPARATION seules : refus COUREUR_INSCRIT_COURSE_EN_PREPARATION portant ces Courses")
    void doit_refuser_un_coureur_inscrit_a_des_courses_en_preparation() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "Backyard A"), enPreparation(COURSE_2, "Backyard B"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnPreparationException.class,
                        refus -> assertThat(refus.courses()).containsExactly(
                                enPreparation(COURSE_1, "Backyard A"), enPreparation(COURSE_2, "Backyard B")));
    }

    @Test
    @DisplayName("CA13 - EN_COURS et EN_PREPARATION : refus EN_COURS portant seulement les Courses EN_COURS")
    void doit_refuser_en_priorite_pour_une_course_en_cours_sans_citer_les_courses_en_preparation() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "Backyard préparation"), enCours(COURSE_2, "Backyard du jour"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).containsExactly(enCours(COURSE_2, "Backyard du jour")));
    }

    @Test
    @DisplayName("CA13 - une seule Course EN_COURS suffit à refuser parmi plusieurs Courses EN_PREPARATION")
    void doit_refuser_pour_une_course_en_cours_parmi_plusieurs_courses_en_preparation() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "A"), enPreparation(COURSE_2, "B"), enCours(COURSE_3, "C"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOf(CoureurInscritCourseEnCoursException.class);
    }

    @Test
    @DisplayName("CA13 - les Courses du refus sont triées par nom sans tenir compte de la casse")
    void doit_trier_les_courses_du_refus_par_nom_insensible_a_la_casse() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "zèbre"), enPreparation(COURSE_2, "Backyard"),
                enPreparation(COURSE_3, "alpha"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnPreparationException.class,
                        refus -> assertThat(refus.courses()).extracting(CourseNonTerminee::nom)
                                .containsExactly("alpha", "Backyard", "zèbre"));
    }

    @Test
    @DisplayName("CA13 - à nom égal, les Courses du refus sont triées par identifiant")
    void doit_departager_les_noms_identiques_par_identifiant() {
        alice();
        consultation.inscrireA(enCours(COURSE_3, "Backyard"), enCours(COURSE_1, "BACKYARD"),
                enCours(COURSE_2, "backyard"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOfSatisfying(CoureurInscritCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).extracting(CourseNonTerminee::id)
                                .containsExactly(COURSE_1, COURSE_2, COURSE_3));
    }

    @Test
    @DisplayName("CA13 - le refus ne contient ni pseudo ni identifiant de Compte, seulement les noms de Courses")
    void doit_ne_divulguer_ni_pseudo_ni_identifiant_de_compte_dans_le_refus() {
        alice();
        consultation.inscrireA(enPreparation(COURSE_1, "Backyard de démo"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .satisfies(refus -> {
                    assertThat(refus.getMessage()).contains("Backyard de démo")
                            .doesNotContain(PSEUDO_ALICE).doesNotContain(ID_ALICE.toString());
                    assertThat(refus.toString()).doesNotContain(PSEUDO_ALICE).doesNotContain(ID_ALICE.toString());
                });
    }

    @Test
    @DisplayName("CA13 - un refus 409 n'écrit rien et ne ferme aucune session")
    void doit_ne_rien_ecrire_ni_fermer_de_session_quand_le_coureur_est_inscrit() {
        alice();
        consultation.inscrireA(enCours(COURSE_1, "Backyard du jour"));

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE))
                .isInstanceOf(CoureurInscritCourseEnCoursException.class);

        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA13 - un Compte inconnu est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_compte_inconnu_sans_consulter_les_inscriptions() {
        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_INCONNU)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @ParameterizedTest(name = "CA13 - un compte {0} est introuvable : aucune consultation, aucune écriture")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA13 - un compte qui n'est pas coureur est introuvable")
    void doit_refuser_un_compte_qui_n_est_pas_coureur(Role role) {
        compte(ID_AUTRE, "Cible", role, true, "empreinte");

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_AUTRE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_AUTRE).orElseThrow().role()).isEqualTo(role);
    }

    @Test
    @DisplayName("CA13 - l'appelant lui-même (admin) est introuvable comme toute cible non coureur")
    void doit_refuser_le_compte_de_l_appelant() {
        compte(ID_NADIA, "Nadia", Role.ADMIN, true, "empreinte-nadia");

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_NADIA)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA13 - un coureur anonymisé est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_coureur_anonymise() {
        depot.ajouter(alice().anonymiser());

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA13 - un coureur inactif est introuvable : aucune consultation, aucune écriture")
    void doit_refuser_un_coureur_inactif() {
        compte(ID_ALICE, PSEUDO_ALICE, Role.COUREUR, false, "empreinte-alice");

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(consultation.appels).isEmpty();
        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA13 - un second passage sur le même Compte est introuvable (non idempotent)")
    void doit_refuser_un_second_passage_du_meme_compte() {
        alice();
        passer.executer(ID_NADIA, ID_ALICE);

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_ALICE);
    }

    @Test
    @DisplayName("CA13 - panne de la consultation des Inscriptions : aucune écriture ni session fermée")
    void doit_ne_rien_ecrire_quand_la_consultation_echoue() {
        alice();
        consultation.panne = new IllegalStateException("panne de la consultation");

        assertThatThrownBy(() -> passer.executer(ID_NADIA, ID_ALICE)).isSameAs(consultation.panne);

        assertAucuneEcritureNiSession();
        assertThat(depot.trouverParId(ID_ALICE).orElseThrow().role()).isEqualTo(Role.COUREUR);
    }

    @Test
    @DisplayName("CA13 - panne de mettreAJour : aucune session fermée")
    void doit_ne_fermer_aucune_session_quand_la_mise_a_jour_echoue() {
        DepotAvecSuppression depotEnPanne = new DepotAvecSuppression(journal) {
            @Override
            public void mettreAJour(Compte compte) {
                throw new IllegalStateException("panne du dépôt");
            }
        };
        depotEnPanne.ajouter(Compte.reconstituer(ID_ALICE, new Pseudo(PSEUDO_ALICE), "e", Role.COUREUR, CREE_LE, true));
        PasserCoureurEnBenevole cas = new PasserCoureurEnBenevole(depotEnPanne, sessions, consultation);

        assertThatThrownBy(() -> cas.executer(ID_NADIA, ID_ALICE)).isInstanceOf(IllegalStateException.class);

        assertThat(sessions.toutesLesSessions).isEmpty();
    }
}
