package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.application.DeclarerCourse.Commande;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.1a : cas d'usage DeclarerCourse (CA3, CA7). La date du jour vient du Clock, fuseau Europe/Paris. */
class DeclarerCourseTest {

    private static final Instant MIDI_UTC_LE_3_OCTOBRE = Instant.parse("2026-10-03T10:00:00Z");
    // 22h30 UTC le 3 octobre = 00h30 le 4 octobre à Paris (UTC+2 en octobre)
    private static final Instant SOIR_UTC_LE_3_OCTOBRE = Instant.parse("2026-10-03T22:30:00Z");

    private final DepotCoursesEnMemoire depot = new DepotCoursesEnMemoire();

    // ---------- CA7 ----------

    @Test
    @DisplayName("CA7 - enregistre et renvoie la Course de référence, EN_PREPARATION, même id")
    void doit_enregistrer_et_renvoyer_la_course_de_reference() {
        Course course = cas(MIDI_UTC_LE_3_OCTOBRE).executer(courseDeReference());

        assertThat(depot.enregistrees).containsExactly(course);
        assertThat(course.id()).isNotNull();
        assertThat(course.nom()).isEqualTo("Backyard des Crêtes");
        assertThat(course.date()).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(course.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(course.parametresBoucle()).isEqualTo(new ParametresBoucle(6706, 60, 120));
        assertThat(course.nombreMaxParticipants()).isEqualTo(50);
        assertThat(course.nombreMaxBoucles()).isEqualTo(24);
    }

    @Test
    @DisplayName("CA7 - une violation lève l'exception de validation et le dépôt reste vide")
    void doit_laisser_le_depot_vide_quand_la_saisie_est_invalide() {
        Commande invalide = new Commande("ab", LocalDate.of(2026, 11, 14), 6706, 60, 120, 50, 24);

        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(invalide))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("NOM_LONGUEUR"));
        assertThat(depot.enregistrees).isEmpty();
    }

    @Test
    @DisplayName("CA7 - deux déclarations de même nom et même date : 2 Courses enregistrées, ids distincts (pas d'unicité)")
    void doit_accepter_deux_courses_de_meme_nom_et_meme_date() {
        DeclarerCourse cas = cas(MIDI_UTC_LE_3_OCTOBRE);

        Course premiere = cas.executer(courseDeReference());
        Course seconde = cas.executer(courseDeReference());

        assertThat(depot.enregistrees).containsExactly(premiere, seconde);
        assertThat(premiere.id()).isNotEqualTo(seconde.id());
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - Clock à 22h30 UTC le 3/10 (4/10 à Paris) : la date 2026-10-03 est passée")
    void doit_refuser_comme_passee_la_date_du_jour_utc_quand_il_est_deja_le_lendemain_a_paris() {
        assertThatThrownBy(() -> cas(SOIR_UTC_LE_3_OCTOBRE).executer(avecDate(LocalDate.of(2026, 10, 3))))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e -> {
                    assertThat(e.violations()).hasSize(1);
                    assertThat(e.violations().get(0).champ()).isEqualTo("date");
                    assertThat(e.violations().get(0).code()).isEqualTo("DATE_PASSEE");
                });
        assertThat(depot.enregistrees).isEmpty();
    }

    @Test
    @DisplayName("CA3 - Clock à 22h30 UTC le 3/10 : 2026-10-04 (jour à Paris) est acceptée")
    void doit_accepter_le_jour_courant_a_paris() {
        Course course = cas(SOIR_UTC_LE_3_OCTOBRE).executer(avecDate(LocalDate.of(2026, 10, 4)));

        assertThat(course.date()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("CA3 - Clock à 22h30 UTC le 3/10 : 2031-10-04 acceptée, 2031-10-05 refusée (DATE_TROP_LOINTAINE)")
    void doit_borner_a_cinq_ans_a_partir_du_jour_a_paris() {
        assertThat(cas(SOIR_UTC_LE_3_OCTOBRE).executer(avecDate(LocalDate.of(2031, 10, 4))).date())
                .isEqualTo(LocalDate.of(2031, 10, 4));

        assertThatThrownBy(() -> cas(SOIR_UTC_LE_3_OCTOBRE).executer(avecDate(LocalDate.of(2031, 10, 5))))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("DATE_TROP_LOINTAINE"));
    }

    @Test
    @DisplayName("CA3 - Clock à 10h00 UTC le 3/10 : la date 2026-10-03 est acceptée")
    void doit_accepter_le_3_octobre_quand_il_est_midi_utc() {
        Course course = cas(MIDI_UTC_LE_3_OCTOBRE).executer(avecDate(LocalDate.of(2026, 10, 3)));

        assertThat(course.date()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("CA3 - date absente : DATE_REQUISE")
    void doit_refuser_une_date_absente() {
        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(avecDate(null)))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("DATE_REQUISE"));
    }

    private DeclarerCourse cas(Instant maintenant) {
        return new DeclarerCourse(depot, Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private static Commande courseDeReference() {
        return avecDate(LocalDate.of(2026, 11, 14));
    }

    private static Commande avecDate(LocalDate date) {
        return new Commande("  Backyard des Crêtes  ", date, 6706, 60, 120, 50, 24);
    }

    private static final class DepotCoursesEnMemoire implements DepotCourses {
        final List<Course> enregistrees = new ArrayList<>();

        @Override
        public void enregistrer(Course course) {
            enregistrees.add(course);
        }

        @Override
        public List<Course> toutes() {
            return List.copyOf(enregistrees);
        }

        /** Recherche par identifiant, ajoutée au port en 2.2 (RG12). */
        public java.util.Optional<Course> parId(java.util.UUID id) {
            return enregistrees.stream().filter(c -> c.id().equals(id)).findFirst();
        }
    }
}
