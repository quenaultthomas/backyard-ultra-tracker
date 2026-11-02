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
 * Incrément 9.3, CA2 : retrait d'un bénévole supprimé de toutes ses Courses (RG3, RG4, RG8).
 * Signatures attendues, dans {@code courses.application} (aucune dépendance vers {@code comptes}) :
 * <ul>
 *   <li>{@code new RetirerBenevoleDeToutesSesCourses(DepotCourses)} et
 *       {@code RetraitAffectations retirer(UUID benevoleId)} ;</li>
 *   <li>{@code record RetraitAffectations(int retirees, List<CourseBloquante> bloquantes)} (imbriqué ou non) ;</li>
 *   <li>{@code record CourseBloquante(UUID id, String nom)}.</li>
 * </ul>
 * Refus : {@code bloquantes} non vide, {@code retirees} = 0 et aucune Course enregistrée.
 */
class RetirerBenevoleDeToutesSesCoursesTest {

    // Identifiants volontairement déclarés dans le désordre : l'ordre de verrouillage doit être l'ordre croissant.
    private static final UUID P2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID P = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID T = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID C2 = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID LEO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID MARC = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID SANS_AFFECTATION = UUID.fromString("00000000-0000-0000-0000-0000000000b3");

    private final DepotEnMemoire depot = new DepotEnMemoire();
    private final RetirerBenevoleDeToutesSesCourses retirer = new RetirerBenevoleDeToutesSesCourses(depot);

    /** Jeu de référence de la spec : Léo affecté à P, P2, T ; Marc à P et C. */
    private void jeuDeReference() {
        depot.enregistrer(course(T, "Terminée", StatutCourse.TERMINEE, Set.of(LEO)));
        depot.enregistrer(course(P2, "Préparation 2", StatutCourse.EN_PREPARATION, Set.of(LEO)));
        depot.enregistrer(course(C, "Backyard C", StatutCourse.EN_COURS, Set.of(MARC)));
        depot.enregistrer(course(P, "Préparation 1", StatutCourse.EN_PREPARATION, Set.of(LEO, MARC)));
        depot.enregistrements = 0;
    }

    private Set<UUID> affectes(UUID idCourse) {
        return depot.parId(idCourse).orElseThrow().benevolesAffectes();
    }

    @Test
    @DisplayName("CA2 - Léo affecté à P, P2 et T : retiré de 3 Courses, aucune bloquante")
    void doit_retirer_le_benevole_de_toutes_ses_courses_sans_course_en_cours() {
        jeuDeReference();

        RetraitAffectations resultat = retirer.retirer(LEO);

        assertThat(resultat.retirees()).isEqualTo(3);
        assertThat(resultat.bloquantes()).isEmpty();
    }

    @Test
    @DisplayName("CA2 - Léo n'est plus affecté à P, P2 ni T (y compris la Course TERMINEE) et Marc reste dans P")
    void doit_retirer_aussi_des_courses_terminees_et_conserver_les_autres_benevoles() {
        jeuDeReference();

        retirer.retirer(LEO);

        assertThat(affectes(P)).containsExactly(MARC);
        assertThat(affectes(P2)).isEmpty();
        assertThat(affectes(T)).isEmpty();
        assertThat(depot.parId(T).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(affectes(C)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA2 - chaque Course modifiée est enregistrée")
    void doit_enregistrer_chaque_course_modifiee() {
        jeuDeReference();

        retirer.retirer(LEO);

        assertThat(depot.enregistrements).isEqualTo(3);
    }

    @Test
    @DisplayName("CA2 - un bénévole sans affectation : retrait de 0, aucune Course chargée sous verrou ni enregistrée")
    void doit_retourner_zero_pour_un_benevole_sans_affectation() {
        jeuDeReference();

        RetraitAffectations resultat = retirer.retirer(SANS_AFFECTATION);

        assertThat(resultat.retirees()).isZero();
        assertThat(resultat.bloquantes()).isEmpty();
        assertThat(depot.chargementsPourModification).isEmpty();
        assertThat(depot.enregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - Marc affecté à P (EN_PREPARATION) et C (EN_COURS) : seule C est bloquante, avec identifiant et nom")
    void doit_retourner_la_course_en_cours_comme_bloquante() {
        jeuDeReference();

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.bloquantes()).containsExactly(new CourseBloquante(C, "Backyard C"));
    }

    @Test
    @DisplayName("CA2 - refus : aucune Course n'est modifiée ni enregistrée, P garde Marc")
    void doit_ne_modifier_aucune_course_quand_une_course_en_cours_bloque() {
        jeuDeReference();

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.retirees()).isZero();
        assertThat(depot.enregistrements).isZero();
        assertThat(affectes(P)).containsExactlyInAnyOrder(LEO, MARC);
        assertThat(affectes(C)).containsExactly(MARC);
    }

    @Test
    @DisplayName("CA2 - tout ou rien : une Course EN_COURS bloque même quand EN_PREPARATION et TERMINEE précèdent")
    void doit_tout_refuser_quand_preparation_terminee_et_en_cours_sont_melangees() {
        depot.enregistrer(course(P, "Préparation", StatutCourse.EN_PREPARATION, Set.of(MARC)));
        depot.enregistrer(course(T, "Terminée", StatutCourse.TERMINEE, Set.of(MARC)));
        depot.enregistrer(course(C, "Backyard C", StatutCourse.EN_COURS, Set.of(MARC)));
        depot.enregistrements = 0;

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.bloquantes()).extracting(CourseBloquante::id).containsExactly(C);
        assertThat(affectes(P)).containsExactly(MARC);
        assertThat(affectes(T)).containsExactly(MARC);
        assertThat(depot.enregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - deux Courses EN_COURS : les deux sont bloquantes, triées par nom sans tenir compte de la casse")
    void doit_trier_les_courses_bloquantes_par_nom_insensible_a_la_casse() {
        depot.enregistrer(course(C, "Zèbre", StatutCourse.EN_COURS, Set.of(MARC)));
        depot.enregistrer(course(C2, "alpha", StatutCourse.EN_COURS, Set.of(MARC)));

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.bloquantes()).containsExactly(
                new CourseBloquante(C2, "alpha"), new CourseBloquante(C, "Zèbre"));
    }

    @Test
    @DisplayName("CA2 - deux Courses EN_COURS de même nom : départagées par identifiant")
    void doit_departager_les_noms_identiques_par_identifiant() {
        depot.enregistrer(course(C2, "Backyard", StatutCourse.EN_COURS, Set.of(MARC)));
        depot.enregistrer(course(C, "backyard", StatutCourse.EN_COURS, Set.of(MARC)));

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.bloquantes()).extracting(CourseBloquante::id).containsExactly(C, C2);
    }

    @Test
    @DisplayName("CA2 - les Courses sont chargées sous verrou dans l'ordre croissant de leur identifiant")
    void doit_verrouiller_les_courses_dans_l_ordre_croissant_des_identifiants() {
        jeuDeReference();

        retirer.retirer(LEO);

        assertThat(depot.chargementsPourModification).containsExactly(P, P2, T);
    }

    @Test
    @DisplayName("CA2 - le statut est relu sous verrou : une Course passée EN_COURS entre la lecture et le verrou bloque")
    void doit_bloquer_une_course_demarree_entre_la_lecture_et_le_verrou() {
        jeuDeReference();
        depot.statutAuVerrou.put(P2, StatutCourse.EN_COURS);

        RetraitAffectations resultat = retirer.retirer(LEO);

        assertThat(resultat.bloquantes()).extracting(CourseBloquante::id).containsExactly(P2);
        assertThat(affectes(P)).containsExactlyInAnyOrder(LEO, MARC);
        assertThat(affectes(T)).containsExactly(LEO);
        assertThat(depot.enregistrements).isZero();
    }

    @Test
    @DisplayName("CA2 - une Course disparue entre la lecture et le verrou est ignorée, sans exception ni comptage")
    void doit_ignorer_une_course_supprimee_entre_la_lecture_et_le_verrou() {
        jeuDeReference();
        depot.disparaitAuVerrou.add(P2);

        RetraitAffectations resultat = retirer.retirer(LEO);

        assertThat(resultat.retirees()).isEqualTo(2);
        assertThat(resultat.bloquantes()).isEmpty();
        assertThat(affectes(P)).containsExactly(MARC);
        assertThat(affectes(T)).isEmpty();
    }

    @Test
    @DisplayName("CA2 - retirer Marc d'une Course préparée ne touche pas les Courses où il n'est pas affecté")
    void doit_ne_pas_toucher_les_courses_ou_le_benevole_n_est_pas_affecte() {
        depot.enregistrer(course(P, "Préparation", StatutCourse.EN_PREPARATION, Set.of(MARC)));
        depot.enregistrer(course(P2, "Autre", StatutCourse.EN_PREPARATION, Set.of(LEO)));
        depot.enregistrements = 0;

        RetraitAffectations resultat = retirer.retirer(MARC);

        assertThat(resultat.retirees()).isEqualTo(1);
        assertThat(affectes(P2)).containsExactly(LEO);
        assertThat(depot.chargementsPourModification).containsExactly(P);
    }

    // --- fabriques et doubles -------------------------------------------------------------------------------

    private static Course course(UUID id, String nom, StatutCourse statut, Set<UUID> benevoles) {
        return DepotCoursesAvecAffectationsDeTest.course(id, nom, LocalDate.of(2026, 11, 14), statut, benevoles);
    }

    /**
     * Dépôt en mémoire : trace les chargements sous verrou, peut faire disparaître une Course au verrou ou la faire
     * apparaître sous un autre statut que celui de la lecture simple (démarrage concurrent).
     */
    private static final class DepotEnMemoire implements DepotCourses {
        private final Map<UUID, Course> courses = new LinkedHashMap<>();
        final List<UUID> chargementsPourModification = new ArrayList<>();
        final Set<UUID> disparaitAuVerrou = new HashSet<>();
        final Map<UUID, StatutCourse> statutAuVerrou = new LinkedHashMap<>();
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
            if (disparaitAuVerrou.contains(id)) {
                return Optional.empty();
            }
            Course lue = courses.get(id);
            StatutCourse statut = statutAuVerrou.get(id);
            if (lue == null || statut == null) {
                return Optional.ofNullable(lue);
            }
            Course sousVerrou = DepotCoursesAvecAffectationsDeTest.course(id, lue.nom(), lue.date(), statut,
                    lue.benevolesAffectes());
            courses.put(id, sousVerrou);
            return Optional.of(sousVerrou);
        }
    }
}
