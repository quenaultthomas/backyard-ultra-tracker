package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.DoublesChangementRoleDeTest.ConsultationCoursesEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.DepotAvecSuppression;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteNonGerableException;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 9.7, CA3 : journal du passage en admin (RG10). Le journal du projet est un {@code System.Logger}, acheminé
 * vers Logback (slf4j-jdk-platform-logging) : l'appender {@link JournalApplicatif} est posé sur la racine Logback pour
 * capturer les lignes du code de l'application quel que soit le logger précis choisi par l'implémentation (RG11 :
 * déroulé mutualisé). Les lignes sont comparées après formatage.
 */
class PasserCoureurEnAdminJournalTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID ID_PATRON = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID ID_ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
    private static final String PSEUDO = "PseudoSecret";
    private static final String COURSE_A = "Course Alpha Secrete";
    private static final String COURSE_B = "Course Beta Secrete";

    private final List<String> ordre = new ArrayList<>();
    private final DepotAvecSuppression depot = new DepotAvecSuppression(ordre);
    private final SessionsEspion sessions = new SessionsEspion(ordre);
    private final ConsultationCoursesEspion consultation = new ConsultationCoursesEspion(ordre);
    private final PasserCoureurEnAdmin passer = new PasserCoureurEnAdmin(depot, sessions, consultation);

    private JournalApplicatif journal;

    @BeforeEach
    void capturerLeJournal() {
        journal = JournalApplicatif.demarrerEnDebug();
    }

    @AfterEach
    void restaurerLeJournal() {
        journal.fermer();
    }

    private List<String> lignes() {
        return journal.messages();
    }

    private void coureurAlice() {
        depot.ajouter(Compte.reconstituer(ID_ALICE, new Pseudo(PSEUDO), "empreinte", Role.COUREUR, CREE_LE, true));
    }

    private void assertAucuneDonneePersonnelle() {
        assertThat(String.join("\n", lignes())).doesNotContain(PSEUDO).doesNotContain(COURSE_A)
                .doesNotContain(COURSE_B).doesNotContain("empreinte");
    }

    @Test
    @DisplayName("CA3 - un succès journalise « Coureur passé en admin » une fois, avec les deux identifiants")
    void doit_journaliser_une_seule_fois_le_passage_en_admin() {
        coureurAlice();

        passer.executer(ID_PATRON, ID_ALICE);

        assertThat(lignes()).containsExactly(
                "Coureur passé en admin (compte " + ID_ALICE + " par " + ID_PATRON + ")");
        assertAucuneDonneePersonnelle();
    }

    @Test
    @DisplayName("CA3 - un refus 409 journalise la ligne de refus avec motif et nombre de Courses, sans nom de Course")
    void doit_journaliser_le_refus_avec_motif_et_nombre_de_courses() {
        coureurAlice();
        consultation.inscrireA(new CourseNonTerminee(UUID.randomUUID(), COURSE_A, Statut.EN_PREPARATION),
                new CourseNonTerminee(UUID.randomUUID(), COURSE_B, Statut.EN_PREPARATION));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE))
                .isInstanceOf(CoureurInscritCourseEnPreparationException.class);

        assertThat(lignes()).containsExactly("Changement de rôle refusé (compte " + ID_ALICE
                + ", motif COUREUR_INSCRIT_COURSE_EN_PREPARATION, 2 course(s))");
        assertAucuneDonneePersonnelle();
    }

    @Test
    @DisplayName("CA3 - un refus 404 (compte inconnu) n'écrit aucune ligne de ce cas d'usage")
    void doit_ne_rien_journaliser_pour_un_compte_introuvable() {
        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_INCONNU)).isInstanceOf(CompteNonGerableException.class);

        assertThat(lignes()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un refus 404 (cible non coureur) n'écrit aucune ligne de ce cas d'usage")
    void doit_ne_rien_journaliser_pour_une_cible_qui_n_est_pas_coureur() {
        depot.ajouter(Compte.reconstituer(ID_ALICE, new Pseudo(PSEUDO), "empreinte", Role.BENEVOLE, CREE_LE, true));

        assertThatThrownBy(() -> passer.executer(ID_PATRON, ID_ALICE)).isInstanceOf(CompteNonGerableException.class);

        assertThat(lignes()).isEmpty();
    }
}
