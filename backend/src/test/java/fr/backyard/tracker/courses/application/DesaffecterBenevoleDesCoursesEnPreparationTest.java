package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 8.1, CA6 : désaffectation d'un bénévole désactivé des Courses EN_PREPARATION (RG5, RG6, RG11).
 * Signature attendue : {@code new DesaffecterBenevoleDesCoursesEnPreparation(DepotCourses)} et
 * {@code int desaffecter(UUID benevoleId)}.
 */
class DesaffecterBenevoleDesCoursesEnPreparationTest {

    // Identifiants volontairement déclarés dans le désordre : l'ordre de verrouillage doit être l'ordre croissant.
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID SANS_AFFECTATION = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private final DepotCoursesVerrouilleDeTest depot = new DepotCoursesVerrouilleDeTest();
    private final DesaffecterBenevoleDesCoursesEnPreparation desaffecter =
            new DesaffecterBenevoleDesCoursesEnPreparation(depot);

    private void garnir() {
        depot.enregistrer(course(T, StatutCourse.TERMINEE, Set.of(LEO)));
        depot.enregistrer(course(P2, StatutCourse.EN_PREPARATION, Set.of(LEO)));
        depot.enregistrer(course(C, StatutCourse.EN_COURS, Set.of(LEO)));
        depot.enregistrer(course(P1, StatutCourse.EN_PREPARATION, Set.of(LEO, MARC)));
        depot.enregistrements = 0;
    }

    private Set<UUID> affectes(UUID idCourse) {
        return depot.parId(idCourse).orElseThrow().benevolesAffectes();
    }

    @Test
    @DisplayName("CA6 - Léo affecté à P1, P2, C et T : retourne 2, le nombre de Courses EN_PREPARATION modifiées")
    void doit_retourner_le_nombre_de_courses_en_preparation_modifiees() {
        garnir();

        int retirees = desaffecter.desaffecter(LEO);

        assertThat(retirees).isEqualTo(2);
    }

    @Test
    @DisplayName("CA6 - Léo est retiré de P1 et de P2")
    void doit_retirer_le_benevole_des_courses_en_preparation() {
        garnir();

        desaffecter.desaffecter(LEO);

        assertThat(affectes(P1)).doesNotContain(LEO);
        assertThat(affectes(P2)).isEmpty();
    }

    @Test
    @DisplayName("CA6 - Léo est conservé dans C (EN_COURS) et T (TERMINEE), statuts inchangés")
    void doit_conserver_le_benevole_dans_les_courses_demarrees_ou_terminees() {
        garnir();

        desaffecter.desaffecter(LEO);

        assertThat(affectes(C)).containsExactly(LEO);
        assertThat(affectes(T)).containsExactly(LEO);
        assertThat(depot.parId(C).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(depot.parId(T).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA6 - Marc reste affecté à P1")
    void doit_laisser_les_autres_benevoles_affectes() {
        garnir();

        desaffecter.desaffecter(LEO);

        assertThat(affectes(P1)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA6 - un bénévole sans affectation : 0, aucune Course modifiée, aucune exception")
    void doit_retourner_zero_pour_un_benevole_sans_affectation() {
        garnir();

        int retirees = desaffecter.desaffecter(SANS_AFFECTATION);

        assertThat(retirees).isZero();
        assertThat(depot.enregistrements).isZero();
        assertThat(affectes(P1)).containsExactlyInAnyOrder(LEO, MARC);
    }

    @Test
    @DisplayName("CA6 - un bénévole affecté seulement à des Courses démarrées ou terminées : 0, tout est conservé")
    void doit_retourner_zero_quand_toutes_les_affectations_sont_conservees() {
        depot.enregistrer(course(C, StatutCourse.EN_COURS, Set.of(LEO)));
        depot.enregistrer(course(T, StatutCourse.TERMINEE, Set.of(LEO)));

        int retirees = desaffecter.desaffecter(LEO);

        assertThat(retirees).isZero();
        assertThat(affectes(C)).containsExactly(LEO);
        assertThat(affectes(T)).containsExactly(LEO);
    }

    @Test
    @DisplayName("CA6 - les Courses sont chargées sous verrou dans l'ordre croissant de leur identifiant")
    void doit_verrouiller_les_courses_dans_l_ordre_croissant_des_identifiants() {
        garnir();

        desaffecter.desaffecter(LEO);

        assertThat(depot.chargementsPourModification).contains(P1, P2).isSorted().doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("CA6 - le statut est relu sous verrou : une Course passée EN_COURS entre la lecture et le verrou conserve Léo")
    void doit_conserver_l_affectation_d_une_course_demarree_entre_la_lecture_et_le_verrou() {
        Course vueEnPreparation = course(P1, StatutCourse.EN_PREPARATION, Set.of(LEO));
        Course lueSousVerrou = course(P1, StatutCourse.EN_COURS, Set.of(LEO));
        DepotCourses concurrent = new DepotQuiDemarreAuVerrou(vueEnPreparation, lueSousVerrou);
        DesaffecterBenevoleDesCoursesEnPreparation cas = new DesaffecterBenevoleDesCoursesEnPreparation(concurrent);

        int retirees = cas.desaffecter(LEO);

        assertThat(retirees).isZero();
        assertThat(lueSousVerrou.benevolesAffectes()).containsExactly(LEO);
        assertThat(vueEnPreparation.benevolesAffectes()).containsExactly(LEO);
    }

    @Test
    @DisplayName("CA6 - une Course supprimée entre la lecture et le verrou est ignorée : pas d'exception, non comptée")
    void doit_ignorer_une_course_supprimee_entre_la_lecture_et_le_verrou() {
        garnir();
        depot.disparaitAuVerrou.add(P2);

        int retirees = desaffecter.desaffecter(LEO);

        assertThat(retirees).isEqualTo(1);
        assertThat(affectes(P1)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA6 - désaffecter Marc ne touche que ses propres affectations")
    void doit_ne_toucher_que_les_affectations_du_benevole_cible() {
        garnir();

        desaffecter.desaffecter(MARC);

        assertThat(affectes(P1)).containsExactly(LEO);
        assertThat(affectes(P2)).containsExactly(LEO);
        assertThat(affectes(C)).containsExactly(LEO);
    }

    // --- fabriques et doubles -------------------------------------------------------------------------------

    private static Course course(UUID id, StatutCourse statut, Set<UUID> benevoles) {
        return DepotCoursesAvecAffectationsDeTest.course(id, "Course " + id, LocalDate.of(2026, 11, 14), statut,
                benevoles);
    }

    /** Double en mémoire qui trace les chargements sous verrou et peut faire disparaître une Course au verrou. */
    private static final class DepotCoursesVerrouilleDeTest implements DepotCourses {
        private final Map<UUID, Course> courses = new LinkedHashMap<>();
        final List<UUID> chargementsPourModification = new ArrayList<>();
        final Set<UUID> disparaitAuVerrou = new HashSet<>();
        int enregistrements;

        @Override
        public void enregistrer(Course course) {
            courses.put(course.id(), course);
            enregistrements++;
        }

        @Override
        public List<Course> toutes() {
            return new ArrayList<>(courses.values());
        }

        @Override
        public Optional<Course> parId(UUID id) {
            return Optional.ofNullable(courses.get(id));
        }

        @Override
        public Optional<Course> parIdPourModification(UUID id) {
            chargementsPourModification.add(id);
            return disparaitAuVerrou.contains(id) ? Optional.empty() : Optional.ofNullable(courses.get(id));
        }
    }

    /** Course vue EN_PREPARATION par une lecture simple, mais EN_COURS quand elle est lue sous verrou. */
    private static final class DepotQuiDemarreAuVerrou implements DepotCourses {
        private final Course vue;
        private final Course sousVerrou;

        DepotQuiDemarreAuVerrou(Course vue, Course sousVerrou) {
            this.vue = vue;
            this.sousVerrou = sousVerrou;
        }

        @Override
        public void enregistrer(Course course) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Course> toutes() {
            return List.of(vue);
        }

        @Override
        public Optional<Course> parId(UUID id) {
            return id.equals(vue.id()) ? Optional.of(vue) : Optional.empty();
        }

        @Override
        public Optional<Course> parIdPourModification(UUID id) {
            return id.equals(sousVerrou.id()) ? Optional.of(sousVerrou) : Optional.empty();
        }
    }
}
