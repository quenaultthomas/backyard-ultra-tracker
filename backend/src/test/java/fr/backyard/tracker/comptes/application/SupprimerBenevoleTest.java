package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.CHARGEMENT_COMPTE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.FERMETURE_TOUTES_SESSIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.LECTURE_SIMPLE;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.REMISE_A_ZERO_COMPTEUR;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.RETRAIT_TOUTES_AFFECTATIONS;
import static fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SUPPRESSION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.3, CA3 : cas d'usage de suppression d'un bénévole (RG1, RG2, RG3, RG4, RG6, RG7, RG9).
 * Signatures attendues :
 * {@code new SupprimerBenevole(DepotComptes, DesaffectationBenevole, InvalidationAutresSessions,
 * RegistreTentativesConnexion, PolitiqueBlocage)} et {@code void executer(UUID appelantId, UUID cibleId)}.
 * Le report de la fermeture des sessions après le commit n'est pas vérifiable sans Spring : ces tests vérifient
 * qu'elle vient en dernier et jamais en cas d'échec (sans transaction active, fermeture immédiate comme en 8.1).
 */
class SupprimerBenevoleTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_NADIA = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID ID_LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID ID_MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final UUID ID_COURSE_C = UUID.fromString("00000000-0000-0000-0000-0000000000c1");

    private final List<String> journal = new ArrayList<>();
    private final DepotAvecSuppression depot = new DepotAvecSuppression(journal);
    private final DesaffectationTotaleEspion desaffectation = new DesaffectationTotaleEspion(journal);
    private final SessionsEspion sessions = new SessionsEspion(journal);
    private final RegistreEspion registre = new RegistreEspion(journal);
    private final SupprimerBenevole supprimer =
            new SupprimerBenevole(depot, desaffectation, sessions, registre, PolitiqueBlocage.parDefaut());

    private Compte compte(UUID id, String pseudo, Role role, boolean actif) {
        return depot.ajouter(Compte.reconstituer(id, new Pseudo(pseudo), "empreinte-" + pseudo, role, CREE_LE, actif));
    }

    private Compte leo() {
        return compte(ID_LEO, "Léo", Role.BENEVOLE, true);
    }

    private void assertAucunEffet() {
        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(desaffectation.appels).isEmpty();
        assertThat(registre.effacements).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - enchaîne chargement sous verrou, retrait des affectations, suppression, remise à zéro du compteur puis fermeture des sessions en dernier")
    void doit_enchainer_les_operations_dans_l_ordre_et_fermer_les_sessions_en_dernier() {
        leo();
        desaffectation.nombreRetourne = 3;

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, RETRAIT_TOUTES_AFFECTATIONS, SUPPRESSION,
                REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - le Compte est chargé sous verrou, jamais par une lecture simple")
    void doit_charger_le_compte_sous_verrou() {
        leo();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(journal).doesNotContain(LECTURE_SIMPLE);
    }

    @Test
    @DisplayName("CA3 - le Compte de Léo est supprimé du dépôt, jamais seulement mis à jour")
    void doit_supprimer_physiquement_le_compte() {
        leo();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(depot.suppressions).containsExactly(ID_LEO);
        assertThat(depot.contient(ID_LEO)).isFalse();
        assertThat(journal).doesNotContain(MISE_A_JOUR);
    }

    @Test
    @DisplayName("CA3 - le retrait des affectations est demandé pour Léo seulement")
    void doit_demander_le_retrait_des_affectations_du_benevole_cible() {
        leo();
        compte(ID_MARC, "Marc", Role.BENEVOLE, true);

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(desaffectation.appels).containsExactly(ID_LEO);
        assertThat(depot.contient(ID_MARC)).isTrue();
    }

    @Test
    @DisplayName("CA3 - le compteur d'échecs de l'ancien pseudo normalisé est remis à zéro")
    void doit_remettre_a_zero_le_compteur_de_l_ancien_pseudo_normalise() {
        Compte leo = leo();

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(registre.effacements).containsExactly(leo.pseudoNormalise());
    }

    @Test
    @DisplayName("CA3 - toutes les sessions de Léo sont fermées, jamais seulement les autres")
    void doit_fermer_toutes_les_sessions_du_benevole_supprime_uniquement() {
        leo();
        compte(ID_MARC, "Marc", Role.BENEVOLE, true);

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
        assertThat(sessions.autresSessions).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un bénévole sans affectation est supprimé (retrait de 0) et ses sessions fermées")
    void doit_supprimer_un_benevole_sans_affectation() {
        leo();
        desaffectation.nombreRetourne = 0;

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(depot.contient(ID_LEO)).isFalse();
        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
    }

    @Test
    @DisplayName("CA3 - un bénévole désactivé suit le même chemin et est supprimé, sans réactivation ni désactivation")
    void doit_supprimer_un_benevole_desactive_comme_un_actif() {
        compte(ID_LEO, "Léo", Role.BENEVOLE, false);

        supprimer.executer(ID_NADIA, ID_LEO);

        assertThat(journal).containsExactly(CHARGEMENT_COMPTE, RETRAIT_TOUTES_AFFECTATIONS, SUPPRESSION,
                REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
        assertThat(depot.misesAJour).isZero();
        assertThat(depot.contient(ID_LEO)).isFalse();
    }

    @ParameterizedTest(name = "CA3 - une cible {0} est introuvable côté bénévoles, sans aucun effet")
    @EnumSource(value = Role.class, names = {"COUREUR", "ADMIN", "ADMIN_MASTER"})
    @DisplayName("CA3 - une cible qui n'est pas bénévole donne une erreur de domaine 404 sans aucun effet")
    void doit_refuser_une_cible_qui_n_est_pas_benevole(Role role) {
        compte(ID_LEO, "Cible", role, true);

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.contient(ID_LEO)).isTrue();
    }

    @Test
    @DisplayName("CA3 - l'appelant admin visant son propre identifiant est introuvable (rôle non géré), sans aucun effet")
    void doit_refuser_un_admin_qui_vise_son_propre_compte() {
        compte(ID_NADIA, "Nadia", Role.ADMIN, true);

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_NADIA)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
    }

    @Test
    @DisplayName("CA3 - un identifiant inconnu (Compte déjà supprimé) est introuvable, sans aucun effet")
    void doit_refuser_un_compte_inconnu() {
        leo();

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_INCONNU)).isInstanceOf(CompteNonGerableException.class);

        assertAucunEffet();
        assertThat(depot.contient(ID_LEO)).isTrue();
    }

    @Test
    @DisplayName("CA3 - un second appel sur un Compte déjà supprimé est introuvable (non idempotent, RG10)")
    void doit_refuser_une_seconde_suppression_du_meme_benevole() {
        leo();
        supprimer.executer(ID_NADIA, ID_LEO);

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isInstanceOf(CompteNonGerableException.class);

        assertThat(depot.suppressions).containsExactly(ID_LEO);
        assertThat(sessions.toutesLesSessions).containsExactly(ID_LEO);
    }

    @Test
    @DisplayName("CA3 - le port signale la Course EN_COURS C : exception portant [C], rien supprimé, aucune session fermée, compteur intact")
    void doit_refuser_quand_le_port_signale_une_course_en_cours() {
        compte(ID_MARC, "Marc", Role.BENEVOLE, true);
        CourseEnCours courseC = new CourseEnCours(ID_COURSE_C, "Backyard C");
        desaffectation.refus = new BenevoleAffecteCourseEnCoursException(List.of(courseC));

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_MARC))
                .isInstanceOfSatisfying(BenevoleAffecteCourseEnCoursException.class,
                        refus -> assertThat(refus.courses()).containsExactly(courseC));

        assertThat(depot.suppressions).isEmpty();
        assertThat(depot.contient(ID_MARC)).isTrue();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
        assertThat(journal).doesNotContain(SUPPRESSION, REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - panne du port de retrait : rien supprimé, aucune session fermée, compteur intact")
    void doit_ne_rien_supprimer_ni_fermer_quand_le_port_echoue() {
        leo();
        desaffectation.panne = new IllegalStateException("panne du port");

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isSameAs(desaffectation.panne);

        assertThat(depot.suppressions).isEmpty();
        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA3 - panne du dépôt à la suppression : aucune session fermée, compteur intact")
    void doit_ne_fermer_aucune_session_quand_la_suppression_echoue() {
        leo();
        depot.echecSuppression = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> supprimer.executer(ID_NADIA, ID_LEO)).isSameAs(depot.echecSuppression);

        assertThat(sessions.toutesLesSessions).isEmpty();
        assertThat(registre.effacements).isEmpty();
        assertThat(journal).doesNotContain(REMISE_A_ZERO_COMPTEUR, FERMETURE_TOUTES_SESSIONS);
    }

    @Test
    @DisplayName("CA3 - le dépôt par défaut ne sait pas supprimer : aucune session fermée")
    void doit_ne_fermer_aucune_session_quand_le_depot_ne_supporte_pas_la_suppression() {
        DepotAvecSuppression sansSuppression = new DepotAvecSuppression(journal) {
            @Override
            public void supprimer(UUID id) {
                throw new UnsupportedOperationException();
            }
        };
        sansSuppression.ajouter(Compte.reconstituer(ID_LEO, new Pseudo("Léo"), "e", Role.BENEVOLE, CREE_LE, true));
        SupprimerBenevole cas = new SupprimerBenevole(sansSuppression, desaffectation, sessions, registre,
                PolitiqueBlocage.parDefaut());

        assertThatThrownBy(() -> cas.executer(ID_NADIA, ID_LEO)).isInstanceOf(UnsupportedOperationException.class);

        assertThat(sessions.toutesLesSessions).isEmpty();
    }

    @Test
    @DisplayName("CA4 - les exceptions du cas d'usage ne contiennent pas le pseudo de la cible")
    void doit_ne_jamais_exposer_le_pseudo_dans_les_exceptions() {
        compte(ID_LEO, "PseudoSecret", Role.COUREUR, true);
        Stream<Throwable> exceptions = Stream.of(
                catchThrowable(() -> supprimer.executer(ID_NADIA, ID_LEO)),
                catchThrowable(() -> supprimer.executer(ID_NADIA, ID_INCONNU)));

        assertThat(exceptions).allSatisfy(exception -> {
            assertThat(String.valueOf(exception.getMessage())).doesNotContain("PseudoSecret");
            assertThat(exception.toString()).doesNotContain("PseudoSecret");
        });
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException exception) {
            return exception;
        }
        throw new AssertionError("Une exception était attendue");
    }
}
