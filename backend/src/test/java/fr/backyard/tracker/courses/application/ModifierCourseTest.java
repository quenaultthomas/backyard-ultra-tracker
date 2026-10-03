package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.application.ModifierCourse.Commande;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.2 : cas d'usage ModifierCourse (CA5). La date du jour vient du Clock, fuseau Europe/Paris. */
class ModifierCourseTest {

    private static final Instant MIDI_UTC_LE_3_OCTOBRE = Instant.parse("2026-10-03T10:00:00Z");
    // 22h30 UTC le 3 octobre = 00h30 le 4 octobre à Paris (UTC+2 en octobre)
    private static final Instant SOIR_UTC_LE_3_OCTOBRE = Instant.parse("2026-10-03T22:30:00Z");

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesEnMemoire depot = new DepotCoursesEnMemoire();

    // ---------- CA5 ----------

    @Test
    @DisplayName("CA5 - enregistre A avec les nouvelles valeurs et la renvoie, B intacte, 2 Courses dans le dépôt")
    void doit_enregistrer_et_renvoyer_la_course_modifiee_sans_toucher_aux_autres() {
        Course b = courseB();
        depot.enregistrer(courseA(StatutCourse.EN_PREPARATION));
        depot.enregistrer(b);

        Course modifiee = cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_A, "  Backyard des Alpes  "));

        assertThat(modifiee.id()).isEqualTo(ID_A);
        assertThat(modifiee.statut()).isEqualTo(StatutCourse.EN_PREPARATION);
        assertThat(modifiee.nom()).isEqualTo("Backyard des Alpes");
        assertThat(modifiee.date()).isEqualTo(LocalDate.of(2026, 12, 5));
        assertThat(modifiee.parametresBoucle()).isEqualTo(new ParametresBoucle(8000, 45, 0));
        assertThat(modifiee.nombreMaxParticipants()).isEqualTo(80);
        assertThat(modifiee.nombreMaxBoucles()).isEqualTo(12);
        assertThat(depot.parId(ID_A)).containsSame(modifiee);
        assertThat(depot.parId(ID_B)).containsSame(b);
        assertThat(depot.toutes()).hasSize(2);
    }

    @Test
    @DisplayName("CA5 - id inconnu : CourseIntrouvableException, dépôt inchangé")
    void doit_lever_course_introuvable_pour_un_id_inconnu() {
        Course a = courseA(StatutCourse.EN_PREPARATION);
        depot.enregistrer(a);

        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_INCONNU, "Backyard des Alpes")))
                .isInstanceOf(CourseIntrouvableException.class);

        assertThat(depot.toutes()).containsExactly(a);
        assertThat(depot.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - saisie invalide : DonneesCourseInvalidesException, dépôt inchangé")
    void doit_lever_les_violations_et_laisser_le_depot_inchange() {
        Course a = courseA(StatutCourse.EN_PREPARATION);
        depot.enregistrer(a);

        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_A, "ab")))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("NOM_LONGUEUR"));

        assertThat(depot.parId(ID_A)).containsSame(a);
        assertThat(depot.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - Course EN_COURS : CourseNonModifiableException, dépôt inchangé")
    void doit_lever_course_non_modifiable_pour_une_course_en_cours() {
        Course a = courseA(StatutCourse.EN_COURS);
        depot.enregistrer(a);

        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_A, "Backyard des Alpes")))
                .isInstanceOf(CourseNonModifiableException.class);

        assertThat(depot.parId(ID_A)).containsSame(a);
        assertThat(depot.nombreDEnregistrements).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - Course TERMINEE avec saisie invalide : le statut prime (CourseNonModifiableException)")
    void doit_privilegier_le_statut_sur_la_saisie_invalide() {
        depot.enregistrer(courseA(StatutCourse.TERMINEE));

        assertThatThrownBy(() -> cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_A, "ab")))
                .isInstanceOf(CourseNonModifiableException.class);
    }

    @Test
    @DisplayName("CA5 - un nom identique à celui d'une autre Course est accepté (pas d'unicité)")
    void doit_accepter_un_nom_identique_a_celui_d_une_autre_course() {
        depot.enregistrer(courseA(StatutCourse.EN_PREPARATION));
        depot.enregistrer(courseB());

        Course modifiee = cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandePour(ID_A, "Piste plate"));

        assertThat(modifiee.nom()).isEqualTo("Piste plate");
        assertThat(depot.parId(ID_B).orElseThrow().nom()).isEqualTo("Piste plate");
        assertThat(depot.toutes()).hasSize(2);
    }

    @Test
    @DisplayName("CA5 - Clock à 22h30 UTC le 3/10 (4/10 à Paris) : la date 2026-10-03 est passée pour A")
    void doit_calculer_la_date_du_jour_a_paris_et_refuser_la_veille() {
        Course a = courseA(StatutCourse.EN_PREPARATION);
        depot.enregistrer(a);
        Commande veille = commandeAvecDate(ID_A, LocalDate.of(2026, 10, 3));

        assertThatThrownBy(() -> cas(SOIR_UTC_LE_3_OCTOBRE).executer(veille))
                .isInstanceOfSatisfying(DonneesCourseInvalidesException.class, e ->
                        assertThat(e.violations()).extracting(v -> v.code()).containsExactly("DATE_PASSEE"));
        assertThat(depot.parId(ID_A)).containsSame(a);
    }

    @Test
    @DisplayName("CA5 - Clock à 22h30 UTC le 3/10 : la date 2026-10-04 (jour à Paris) est acceptée")
    void doit_accepter_le_jour_courant_a_paris() {
        depot.enregistrer(courseA(StatutCourse.EN_PREPARATION));

        Course modifiee = cas(SOIR_UTC_LE_3_OCTOBRE).executer(commandeAvecDate(ID_A, LocalDate.of(2026, 10, 4)));

        assertThat(modifiee.date()).isEqualTo(LocalDate.of(2026, 10, 4));
    }

    @Test
    @DisplayName("CA5 - Clock à 10h00 UTC le 3/10 : la date 2026-10-03 est acceptée")
    void doit_accepter_le_3_octobre_quand_il_est_midi_utc() {
        depot.enregistrer(courseA(StatutCourse.EN_PREPARATION));

        Course modifiee = cas(MIDI_UTC_LE_3_OCTOBRE).executer(commandeAvecDate(ID_A, LocalDate.of(2026, 10, 3)));

        assertThat(modifiee.date()).isEqualTo(LocalDate.of(2026, 10, 3));
    }

    @Test
    @DisplayName("CA5 - date enregistrée passée et inchangée : acceptée (exemption portée par le domaine)")
    void doit_accepter_une_date_passee_inchangee() {
        LocalDate datePassee = LocalDate.of(2026, 10, 1);
        depot.enregistrer(Course.reconstituer(ID_A, "Backyard des Crêtes", datePassee, StatutCourse.EN_PREPARATION,
                new ParametresBoucle(6706, 60, 120), 50, 24));

        Course modifiee = cas(MIDI_UTC_LE_3_OCTOBRE).executer(
                new Commande(ID_A, "Backyard des Crêtes", datePassee, 6706, 45, 120, 50, 24));

        assertThat(modifiee.parametresBoucle().dureeMinutes()).isEqualTo(45);
        assertThat(depot.parId(ID_A)).containsSame(modifiee);
    }

    // ---------- utilitaires ----------

    private ModifierCourse cas(Instant maintenant) {
        return new ModifierCourse(depot, Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private static Course courseA(StatutCourse statut) {
        return Course.reconstituer(ID_A, "Backyard des Crêtes", LocalDate.of(2026, 11, 14), statut,
                new ParametresBoucle(6706, 60, 120), 50, 24);
    }

    private static Course courseB() {
        return Course.reconstituer(ID_B, "Piste plate", LocalDate.of(2026, 12, 1), StatutCourse.EN_PREPARATION,
                new ParametresBoucle(400, 30, 0), 10, 5);
    }

    private static Commande commandePour(UUID id, String nom) {
        return new Commande(id, nom, LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12);
    }

    private static Commande commandeAvecDate(UUID id, LocalDate date) {
        return new Commande(id, "Backyard des Crêtes", date, 6706, 60, 120, 50, 24);
    }

    private static final class DepotCoursesEnMemoire implements DepotCourses {
        private final Map<UUID, Course> courses = new LinkedHashMap<>();
        int nombreDEnregistrements;

        @Override
        public void enregistrer(Course course) {
            nombreDEnregistrements++;
            courses.put(course.id(), course);
        }

        @Override
        public List<Course> toutes() {
            return List.copyOf(courses.values());
        }

        @Override
        public Optional<Course> parId(UUID id) {
            return Optional.ofNullable(courses.get(id));
        }
    }
}
