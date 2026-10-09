package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseHorsDateException;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarrableException;
import fr.backyard.tracker.courses.domaine.CourseSansInscritException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 4.1 : cas d'usage DemarrerCourse (CA3 ; RG1, RG2, RG4 à RG6, RG10, RG11). */
class DemarrerCourseTest {

    private static final Instant MAINTENANT = Instant.parse("2026-10-09T14:03:27.654Z");
    private static final LocalDate LE_9 = LocalDate.of(2026, 10, 9);
    private static final LocalDate LE_8 = LocalDate.of(2026, 10, 8);
    private static final LocalDate LE_10 = LocalDate.of(2026, 10, 10);

    private static final UUID X = id(1);
    private static final UUID Y = id(2);
    private static final UUID V = id(3);
    private static final UUID L = id(4);
    private static final UUID W = id(5);
    private static final UUID Z = id(6);
    private static final UUID T = id(7);
    private static final UUID INCONNUE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();

    // ---------- CA3 : succès ----------

    @Test
    @DisplayName("CA3 - X est enregistrée EN_COURS avec demarreeLe tronqué, chargée sous verrou, Y et les autres intactes")
    void doit_demarrer_la_course_sous_verrou_sans_toucher_aux_autres() {
        garnirLesDepots();
        List<Inscription> inscriptionsAvant = List.copyOf(depotInscriptions.inscriptions);

        Course demarree = casAu(MAINTENANT).executer(X);

        assertThat(demarree.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(demarree.demarreeLe()).contains(Instant.parse("2026-10-09T14:03:27Z"));
        assertThat(depotCourses.parId(X)).containsSame(demarree);
        assertThat(depotCourses.chargementsPourModification).containsExactly(X);
        assertThat(depotCourses.parId(Y).orElseThrow().statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(depotCourses.parId(Y).orElseThrow().demarreeLe()).isEmpty();
        assertThat(depotCourses.parId(Z).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(depotCourses.toutes()).hasSize(7);
        assertThat(depotInscriptions.inscriptions).containsExactlyElementsOf(inscriptionsAvant);
    }

    @Test
    @DisplayName("CA3 - un second démarrage de X est refusé et conserve le demarreeLe d'origine")
    void doit_refuser_un_second_demarrage_et_conserver_la_premiere_heure() {
        garnirLesDepots();
        casAu(MAINTENANT).executer(X);

        assertThatThrownBy(() -> casAu(MAINTENANT.plusSeconds(120)).executer(X))
                .isInstanceOf(CourseNonDemarrableException.class);

        assertThat(depotCourses.parId(X).orElseThrow().demarreeLe()).contains(Instant.parse("2026-10-09T14:03:27Z"));
    }

    // ---------- CA3 : refus ----------

    @Test
    @DisplayName("CA3 - V datée de la veille : CourseHorsDateException, V inchangée")
    void doit_refuser_une_course_datee_de_la_veille() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(V)).isInstanceOf(CourseHorsDateException.class);

        assertCourseInchangee(V, StatutCourse.EN_PREPARATION);
    }

    @Test
    @DisplayName("CA3 - L datée du lendemain : CourseHorsDateException, L inchangée")
    void doit_refuser_une_course_datee_du_lendemain() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(L)).isInstanceOf(CourseHorsDateException.class);

        assertCourseInchangee(L, StatutCourse.EN_PREPARATION);
    }

    @Test
    @DisplayName("CA3 - W sans inscription : CourseSansInscritException, W inchangée")
    void doit_refuser_une_course_sans_inscrit() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(W)).isInstanceOf(CourseSansInscritException.class);

        assertCourseInchangee(W, StatutCourse.EN_PREPARATION);
    }

    @Test
    @DisplayName("CA3 - Z EN_COURS : CourseNonDemarrableException, Z inchangée")
    void doit_refuser_une_course_deja_en_cours() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(Z)).isInstanceOf(CourseNonDemarrableException.class);

        assertCourseInchangee(Z, StatutCourse.EN_COURS);
        assertThat(depotCourses.parId(Z).orElseThrow().demarreeLe()).contains(Instant.parse("2026-10-09T08:00:00Z"));
    }

    @Test
    @DisplayName("CA3 - T TERMINEE : CourseNonDemarrableException, T inchangée")
    void doit_refuser_une_course_terminee() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(T)).isInstanceOf(CourseNonDemarrableException.class);

        assertCourseInchangee(T, StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA3 - identifiant inconnu : CourseIntrouvableException, dépôt inchangé")
    void doit_refuser_une_course_inconnue() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(INCONNUE)).isInstanceOf(CourseIntrouvableException.class);

        assertThat(depotCourses.toutes()).hasSize(7);
        assertThat(depotCourses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_PREPARATION);
    }

    @Test
    @DisplayName("CA3 - le nombre d'inscrits contrôlé est celui de la Course visée (W vide malgré les inscrits des autres)")
    void doit_compter_les_inscrits_de_la_course_visee_uniquement() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(MAINTENANT).executer(W)).isInstanceOf(CourseSansInscritException.class);
        assertThat(casAu(MAINTENANT).executer(Y).statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    // ---------- CA3 : fuseau (RG11) ----------

    @Test
    @DisplayName("CA3 - 23:59:59 à Paris le 9 : X (09) démarrée, L (10) refusée")
    void doit_utiliser_le_jour_de_paris_juste_avant_minuit() {
        garnirLesDepots();
        Instant avantMinuitAParis = Instant.parse("2026-10-09T21:59:59Z");

        assertThat(casAu(avantMinuitAParis).executer(X).statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThatThrownBy(() -> casAu(avantMinuitAParis).executer(L)).isInstanceOf(CourseHorsDateException.class);
    }

    @Test
    @DisplayName("CA3 - 00:00:00 à Paris le 10 (22:00 UTC) : X (09) refusée, L (10) démarrée")
    void doit_utiliser_le_jour_de_paris_a_minuit_pile() {
        garnirLesDepots();
        Instant minuitAParis = Instant.parse("2026-10-09T22:00:00Z");

        assertThatThrownBy(() -> casAu(minuitAParis).executer(X)).isInstanceOf(CourseHorsDateException.class);
        Course demarree = casAu(minuitAParis).executer(L);

        assertThat(demarree.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(demarree.demarreeLe()).contains(minuitAParis);
    }

    @Test
    @DisplayName("CA3 - heure d'hiver (UTC+1) : 23:00 UTC le 9 décembre = 00:00 le 10 à Paris")
    void doit_utiliser_le_jour_de_paris_en_heure_d_hiver() {
        LocalDate neuf = LocalDate.of(2026, 12, 9);
        LocalDate dix = LocalDate.of(2026, 12, 10);
        UUID courseDu9 = id(8);
        UUID courseDu10 = id(9);
        depotCourses.enregistrer(course(courseDu9, neuf, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(courseDu10, dix, StatutCourse.EN_PREPARATION, null));
        inscrire(courseDu9);
        inscrire(courseDu10);
        Instant minuitAParis = Instant.parse("2026-12-09T23:00:00Z");

        assertThatThrownBy(() -> casAu(minuitAParis).executer(courseDu9)).isInstanceOf(CourseHorsDateException.class);
        assertThat(casAu(minuitAParis).executer(courseDu10).statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    @Test
    @DisplayName("CA3 - 22:30 UTC le 9 octobre (00:30 le 10 à Paris) : X (09) refusée alors que la date UTC est le 9")
    void doit_refuser_le_jour_utc_quand_il_est_deja_le_lendemain_a_paris() {
        garnirLesDepots();

        assertThatThrownBy(() -> casAu(Instant.parse("2026-10-09T22:30:00Z")).executer(X))
                .isInstanceOf(CourseHorsDateException.class);
    }

    // ---------- utilitaires ----------

    private DemarrerCourse casAu(Instant instant) {
        return new DemarrerCourse(depotCourses, depotInscriptions, Clock.fixed(instant, ZoneOffset.UTC));
    }

    private void garnirLesDepots() {
        depotCourses.enregistrer(course(X, LE_9, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(Y, LE_9, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(V, LE_8, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(L, LE_10, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(W, LE_9, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(Z, LE_9, StatutCourse.EN_COURS, Instant.parse("2026-10-09T08:00:00Z")));
        depotCourses.enregistrer(course(T, LE_9, StatutCourse.TERMINEE, Instant.parse("2026-10-09T07:00:00Z")));
        inscrire(X);
        inscrire(X);
        inscrire(Y);
        inscrire(V);
        inscrire(L);
        inscrire(Z);
        inscrire(T);
    }

    private void inscrire(UUID courseId) {
        int dossard = depotInscriptions.nombreInscrits(courseId) + 1;
        depotInscriptions.enregistrer(Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(),
                dossard, new JetonQr(String.format("%043d", depotInscriptions.inscriptions.size() + 1)),
                StatutInscription.EN_COURSE));
    }

    private void assertCourseInchangee(UUID courseId, StatutCourse statutAttendu) {
        assertThat(depotCourses.parId(courseId).orElseThrow().statut()).isEqualTo(statutAttendu);
        assertThat(depotCourses.parId(courseId).orElseThrow().demarreeLe())
                .isEqualTo(courseId.equals(Z) ? java.util.Optional.of(Instant.parse("2026-10-09T08:00:00Z"))
                        : courseId.equals(T) ? java.util.Optional.of(Instant.parse("2026-10-09T07:00:00Z"))
                        : java.util.Optional.empty());
    }

    private static Course course(UUID id, LocalDate date, StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(id, "Course " + id, date, statut, new ParametresBoucle(6706, 60, 120), 50, 24,
                Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
