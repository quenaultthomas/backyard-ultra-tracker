package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.ANNULATION_INSCRIPTIONS;
import static fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.CONSULTATION_INSCRIPTIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.FERMETURE_TOUTES_SESSIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.REMISE_A_ZERO_COMPTEUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.RETRAIT_TOUTES_AFFECTATIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SUPPRESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.AnnulationEspion;
import fr.backyard.tracker.comptes.application.DoublesAnonymisationDeTest.ConsultationEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.DepotAvecSuppression;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.DesaffectationTotaleEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.RegistreEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException.CourseEnCours;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
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
 * Incrément 9.6a, CA2 : suppression d'un bénévole, avec anonymisation s'il a des Inscriptions (RG1, RG4, RG5, RG6, RG8).
 * Signature attendue : {@code new SupprimerBenevole(DepotComptes, DesaffectationBenevole, InvalidationAutresSessions,
 * RegistreTentativesConnexion, PolitiqueBlocage, AnnulationInscriptions, ConsultationInscriptions)}.
 */
class SupprimerBenevoleAnonymisationTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_COURSE_C = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final List<String> journal = new ArrayList<>();
    private final DepotAvecSuppression depot = new DepotAvecSuppression(journal);
    private final DesaffectationTotaleEspion desaffectation = new DesaffectationTotaleEspion(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final RegistreEspion registre = new RegistreEspion(journal);
    private final AnnulationEspion annulation = new AnnulationEspion(journal);
    private final ConsultationEspion consultation = new ConsultationEspion(journal);
    private final SupprimerBenevole supprimer = new SupprimerBenevole(depot, desaffectation, sessions, registre,
            PolitiqueBlocage.parDefaut(), annulation, consultation);

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private Compte leoSansInscription() {
        return compte(ID_LEO, "Léo", Role.BENEVOLE, true);
    }

    private Compte leoAvecInscription() {
        Compte leo = leoSansInscription();
        consultation.inscrire(ID_LEO);
        return leo;
    }

    private void assertAucuneEcritureNiSession() {
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(registre.effacements).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - sans Inscription : annule les Inscriptions en préparation, consulte, supprime, remet le compteur à zéro, ferme les sessions")
    void doit_supprimer_physiquement_un_benevole_sans_inscription() {
        Compte leo = leoSansInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(annulation.appels).containsExactly(ID_LEO);
        assertThat(consultation.appels).containsExactly(ID_LEO);
        assertThat(depot.suppressions).containsExactly(ID_LEO);
        assertThat(registre.effacements).containsExactly(leo.pseudoNormalise());
        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
        assertThat(journal).doesNotContain(MISE_A_JOUR);
        assertThat(depot.contient(ID_LEO)).isFalse();
    }

    @Test
    @DisplayName("CA2 - l'annulation des Inscriptions précède la consultation, qui précède la suppression (RG4)")
    void doit_annuler_les_inscriptions_puis_consulter_avant_de_supprimer() {
        leoSansInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(journal.indexOf(CHARGEMENT_COMPTE)).isLessThan(journal.indexOf(ANNULATION_INSCRIPTIONS));
        assertThat(journal.indexOf(ANNULATION_INSCRIPTIONS)).isLessThan(journal.indexOf(CONSULTATION_INSCRIPTIONS));
        assertThat(journal.indexOf(CONSULTATION_INSCRIPTIONS)).isLessThan(journal.indexOf(SUPPRESSION));
        assertThat(journal.getLast()).isEqualTo(FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA2 - avec Inscription : le Compte est mis à jour sous sa forme anonymisée et jamais supprimé")
    void doit_anonymiser_un_benevole_avec_inscription_sans_le_supprimer() {
        leoAvecInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isEqualTo(1);
        Compte apres = depot.trouverParId(ID_LEO).orElseThrow();
        assertThat(apres.id()).isEqualTo(ID_LEO);
        assertThat(apres.estAnonyme()).isTrue();
        assertThat(apres.pseudo()).isEqualTo(Pseudo.anonyme());
        assertThat(apres.empreinteMotDePasse()).isNull();
        assertThat(apres.role()).isEqualTo(Role.COUREUR);
        assertThat(apres.creeLe()).isEqualTo(CREE_LE);
    }

    @Test
    @DisplayName("CA2 - avec Inscription : l'ordre est annulation, consultation, retrait des affectations et mise à jour, sessions fermées en dernier")
    void doit_enchainer_les_operations_d_anonymisation_et_fermer_les_sessions_en_dernier() {
        leoAvecInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(journal).contains(CHARGEMENT_COMPTE, ANNULATION_INSCRIPTIONS, CONSULTATION_INSCRIPTIONS,
                RETRAIT_TOUTES_AFFECTATIONS, MISE_A_JOUR, REMISE_A_ZERO_COMPTEUR);
        assertThat(journal.getFirst()).isEqualTo(CHARGEMENT_COMPTE);
        assertThat(journal.indexOf(ANNULATION_INSCRIPTIONS)).isLessThan(journal.indexOf(CONSULTATION_INSCRIPTIONS));
        assertThat(journal.indexOf(CONSULTATION_INSCRIPTIONS)).isLessThan(journal.indexOf(MISE_A_JOUR));
        assertThat(journal.indexOf(RETRAIT_TOUTES_AFFECTATIONS)).isLessThan(journal.indexOf(MISE_A_JOUR));
        assertThat(journal.getLast()).isEqualTo(FERMETURE_TOUTES_SESSIONS);
        assertThat(journal).doesNotContain(SUPPRESSION);
    }

    @Test
    @DisplayName("CA2 - avec Inscription : les affectations sont retirées pour le bénévole cible seulement")
    void doit_retirer_les_affectations_d_un_benevole_anonymise() {
        leoAvecInscription();
        compte(ID_MARC, "Marc", Role.BENEVOLE, true);
        desaffectation.nombreRetourne = 2;

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(desaffectation.appels).containsExactly(ID_LEO);
        assertThat(depot.contient(ID_MARC)).isTrue();
    }

    @Test
    @DisplayName("CA2 - avec Inscription : le compteur d'échecs de l'ancien pseudo normalisé est remis à zéro")
    void doit_remettre_a_zero_le_compteur_de_l_ancien_pseudo_d_un_benevole_anonymise() {
        Compte leo = leoAvecInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(registre.effacements).containsExactly(leo.pseudoNormalise());
        assertThat(registre.effacements).doesNotContain(depot.trouverParId(ID_LEO).orElseThrow().pseudoNormalise());
    }

    @Test
    @DisplayName("CA2 - avec Inscription : toutes les sessions du bénévole sont fermées, celles des autres jamais")
    void doit_fermer_toutes_les_sessions_d_un_benevole_anonymise() {
        leoAvecInscription();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - avec Inscription : un bénévole désactivé est anonymisé et reste désactivé")
    void doit_anonymiser_un_benevole_desactive_sans_modifier_son_etat() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);
        consultation.inscrire(ID_LEO);

        supprimer.executer(ID_NADIA, ID_LEO);

        Compte apres = depot.trouverParId(ID_LEO).orElseThrow();
        assertThat(apres.estAnonyme()).isTrue();
        assertThat(apres.actif()).isFalse();
        assertThat(depot.suppressions).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un seul Compte est anonymisé : les autres bénévoles ne sont pas touchés")
    void doit_anonymiser_uniquement_la_cible() {
        leoAvecInscription();
        Compte marc = compte(ID_MARC, "Marc", Role.BENEVOLE, true);
        consultation.inscrire(ID_MARC);

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(depot.trouverParId(ID_MARC).orElseThrow()).isSameAs(marc);
        assertThat(consultation.appels).containsExactly(ID_LEO);
    }

    @ParameterizedTest(name = "CA2 - une cible {0} est refusée avant toute annulation, anonymisation ou suppression")
    @EnumSource(value = Role.class, names = {"COUREUR", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA2 - une garde de rôle refuse sans anonymisation ni suppression, aucune session fermée")
    void doit_refuser_une_cible_qui_n_est_pas_benevole_sans_aucun_effet(Role role) {
        compte(ID_LEO, "Cible", role, true);
        consultation.inscrire(ID_LEO);

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucuneEcritureNiSession();
        assertThat(annulation.appels).isEmpty();
        assertThat(consultation.appels).isEmpty();
        assertThat(depot.contient(ID_LEO)).isTrue();
    }

    @Test
    @DisplayName("CA2 - 409 EN_COURS : ni anonymisation ni suppression, aucune session fermée, même avec Inscription")
    void doit_refuser_un_benevole_affecte_a_une_course_en_cours_sans_anonymiser() {
        leoAvecInscription();
        CourseEnCours courseC = new CourseEnCours(ID_COURSE_C, "Backyard C");
        desaffectation.refus = new BenevoleAffecteCourseEnCoursException(List.of(courseC));

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO))
                .isInstanceOfSatisfying(BenevoleAffecteCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).containsExactly(courseC));

        assertAucuneEcritureNiSession();
        assertThat(journal).doesNotContain(MISE_A_JOUR, SUPPRESSION, REMISE_A_ZERO_COMPTEUR,
                FERMETURE_TOUTES_SESSIONS);
        assertThat(depot.trouverParId(ID_LEO).orElseThrow().estAnonyme()).isFalse();
    }

    @Test
    @DisplayName("CA2 - panne du port d'annulation des Inscriptions : aucune écriture ni session fermée")
    void doit_ne_rien_ecrire_ni_fermer_quand_l_annulation_echoue() {
        leoAvecInscription();
        annulation.panne = new IllegalStateException("panne de l'annulation");

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isSameAs(annulation.panne);

        assertAucuneEcritureNiSession();
        assertThat(consultation.appels).isEmpty();
    }

    @Test
    @DisplayName("CA2 - panne du port de consultation des Inscriptions : aucune écriture ni session fermée")
    void doit_ne_rien_ecrire_ni_fermer_quand_la_consultation_echoue() {
        leoAvecInscription();
        consultation.panne = new IllegalStateException("panne de la consultation");

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isSameAs(consultation.panne);

        assertAucuneEcritureNiSession();
    }

    @Test
    @DisplayName("CA2 - panne du dépôt à la mise à jour d'anonymisation : compteur intact, aucune session fermée")
    void doit_ne_fermer_aucune_session_quand_la_mise_a_jour_d_anonymisation_echoue() {
        DepotAvecSuppression depotEnPanne = new DepotAvecSuppression(journal) {
            @Override
            public void mettreAJour(Compte compte) {
                throw new IllegalStateException("panne du dépôt");
            }
        };
        depotEnPanne.ajouter(Compte.reconstituer(ID_LEO, new Pseudo("Léo"), "e", Role.BENEVOLE, CREE_LE, true));
        consultation.inscrire(ID_LEO);
        SupprimerBenevole cas = new SupprimerBenevole(depotEnPanne, desaffectation, sessions, registre,
                PolitiqueBlocage.parDefaut(), annulation, consultation);

        assertThatThrownBy(() -> cas.executer(ID_NADIA, ID_LEO)).isInstanceOf(IllegalStateException.class);

        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA2 - un second appel sur un bénévole déjà anonymisé est introuvable (rôle COUREUR), sans effet supplémentaire")
    void doit_refuser_une_seconde_suppression_d_un_benevole_anonymise() {
        leoAvecInscription();
        supprimer.executer(ID_NADIA, ID_LEO);

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.misesAJour).isEqualTo(1);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
    }
}
