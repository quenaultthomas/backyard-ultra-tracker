package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.comptes.application.DoublesChangementRoleDeTest.ConsultationCoursesEspion;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.DepotAvecSuppression;
import fr.backyard.tracker.comptes.application.DoublesSuppressionBenevoleDeTest.SessionsEspion;
import fr.backyard.tracker.comptes.domaine.ChangementRoleRefuseException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee;
import fr.backyard.tracker.comptes.domaine.CourseNonTerminee.Statut;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.Role;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Incrément 9.7, CA9 (RG11) : les cas d'usage {@code PasserCoureurEnBenevole} et {@code PasserCoureurEnAdmin} partagent
 * les mêmes règles de refus. Un même scénario est exécuté sur les deux ; seul le rôle cible diffère. Le test ne fige
 * aucune structure interne : si le déroulé est dupliqué puis modifié d'un côté seulement, les résultats divergent.
 */
class ChangementRoleMutualiseTest {

    private static final Instant CREE_LE = Instant.parse("2026-10-26T09:00:00Z");
    private static final UUID APPELANT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID CIBLE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID C1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID C2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID C3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

    /** Rôle cible et cas d'usage correspondant. */
    enum RoleCible {
        BENEVOLE(Role.BENEVOLE),
        ADMIN(Role.ADMIN);

        final Role role;

        RoleCible(Role role) {
            this.role = role;
        }
    }

    /** Vue commune des deux cas d'usage, pour les exécuter par le même scénario. */
    interface Executeur {
        Compte executer(UUID appelantId, UUID cibleId);
    }

    /** Résultat observable d'un scénario : rôle final ou refus (type, courses). */
    private record Issue(Role roleFinal, Class<?> refus, List<CourseNonTerminee> courses, int sessionsFermees) {
    }

    private static CourseNonTerminee preparation(UUID id, String nom) {
        return new CourseNonTerminee(id, nom, Statut.EN_PREPARATION);
    }

    private static CourseNonTerminee enCours(UUID id, String nom) {
        return new CourseNonTerminee(id, nom, Statut.EN_COURS);
    }

    private static Issue jouer(RoleCible cible, List<CourseNonTerminee> inscriptions) {
        List<String> journal = new ArrayList<>();
        DepotAvecSuppression depot = new DepotAvecSuppression(journal);
        SessionsEspion sessions = new SessionsEspion(journal);
        ConsultationCoursesEspion consultation = new ConsultationCoursesEspion(journal);
        depot.ajouter(Compte.reconstituer(CIBLE, new Pseudo("Alice"), "empreinte", Role.COUREUR, CREE_LE, true));
        consultation.inscrireA(inscriptions.toArray(new CourseNonTerminee[0]));
        Executeur executeur = executeur(cible, depot, sessions, consultation);
        try {
            executeur.executer(APPELANT, CIBLE);
            return new Issue(depot.trouverParId(CIBLE).orElseThrow().role(), null, List.of(),
                    sessions.toutesLesSessions.size());
        } catch (ChangementRoleRefuseException refus) {
            return new Issue(depot.trouverParId(CIBLE).orElseThrow().role(), refus.getClass(), refus.courses(),
                    sessions.toutesLesSessions.size());
        }
    }

    private static Executeur executeur(RoleCible cible, DepotAvecSuppression depot, SessionsEspion sessions,
                                       ConsultationCoursesEspion consultation) {
        return switch (cible) {
            case BENEVOLE -> new PasserCoureurEnBenevole(depot, sessions, consultation)::executer;
            case ADMIN -> new PasserCoureurEnAdmin(depot, sessions, consultation)::executer;
        };
    }

    /** Résultat attendu quel que soit le rôle : identique à celui obtenu pour BENEVOLE, rôle final mis à part. */
    private static void assertMemeIssueQueBenevole(RoleCible cible, List<CourseNonTerminee> inscriptions) {
        Issue reference = jouer(RoleCible.BENEVOLE, inscriptions);
        Issue obtenue = jouer(cible, inscriptions);

        assertThat(obtenue.refus()).isEqualTo(reference.refus());
        assertThat(obtenue.courses()).isEqualTo(reference.courses());
        assertThat(obtenue.sessionsFermees()).isEqualTo(reference.sessionsFermees());
        Role roleAttenduSiPromu = cible.role;
        assertThat(obtenue.roleFinal()).isEqualTo(reference.refus() == null ? roleAttenduSiPromu : Role.COUREUR);
    }

    @ParameterizedTest(name = "CA9 - {0} : aucune Inscription non terminée, promu")
    @EnumSource(RoleCible.class)
    @DisplayName("CA9 - sans Course non terminée (aucune Inscription ou seulement TERMINEE), les deux rôles sont promus")
    void doit_promouvoir_de_la_meme_facon_sans_course_non_terminee(RoleCible cible) {
        assertMemeIssueQueBenevole(cible, List.of());
        assertThat(jouer(cible, List.of()).roleFinal()).isEqualTo(cible.role);
        assertThat(jouer(cible, List.of()).sessionsFermees()).isEqualTo(1);
    }

    @ParameterizedTest(name = "CA9 - {0} : Courses EN_PREPARATION")
    @EnumSource(RoleCible.class)
    @DisplayName("CA9 - Courses EN_PREPARATION : même exception, mêmes Courses, même ordre")
    void doit_refuser_de_la_meme_facon_des_courses_en_preparation(RoleCible cible) {
        List<CourseNonTerminee> inscriptions = List.of(preparation(C2, "beta"), preparation(C1, "Alpha"));

        assertMemeIssueQueBenevole(cible, inscriptions);
        Issue issue = jouer(cible, inscriptions);
        assertThat(issue.refus().getSimpleName()).isEqualTo("CoureurInscritCourseEnPreparationException");
        assertThat(issue.courses()).containsExactly(preparation(C1, "Alpha"), preparation(C2, "beta"));
        assertThat(issue.sessionsFermees()).isZero();
    }

    @ParameterizedTest(name = "CA9 - {0} : Course EN_COURS")
    @EnumSource(RoleCible.class)
    @DisplayName("CA9 - Course EN_COURS : même exception, mêmes Courses")
    void doit_refuser_de_la_meme_facon_une_course_en_cours(RoleCible cible) {
        List<CourseNonTerminee> inscriptions = List.of(enCours(C1, "Backyard du jour"));

        assertMemeIssueQueBenevole(cible, inscriptions);
        assertThat(jouer(cible, inscriptions).refus().getSimpleName())
                .isEqualTo("CoureurInscritCourseEnCoursException");
    }

    @ParameterizedTest(name = "CA9 - {0} : mélange EN_COURS et EN_PREPARATION")
    @EnumSource(RoleCible.class)
    @DisplayName("CA9 - mélange EN_COURS et EN_PREPARATION : refus EN_COURS avec les seules Courses EN_COURS, triées")
    void doit_refuser_de_la_meme_facon_un_melange_de_statuts(RoleCible cible) {
        List<CourseNonTerminee> inscriptions = List.of(preparation(C1, "Prep"), enCours(C3, "zeta"),
                enCours(C2, "Alpha"));

        assertMemeIssueQueBenevole(cible, inscriptions);
        Issue issue = jouer(cible, inscriptions);
        assertThat(issue.refus().getSimpleName()).isEqualTo("CoureurInscritCourseEnCoursException");
        assertThat(issue.courses()).containsExactly(enCours(C2, "Alpha"), enCours(C3, "zeta"));
    }
}
