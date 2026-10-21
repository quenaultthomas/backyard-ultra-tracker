package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerCourses.CourseListee;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.lang.reflect.Constructor;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 6.4 : ListerCoursesPubliques regroupe les Courses par statut enregistré (enCours, aVenir, terminees) et
 * trie chaque rubrique (CA1 à CA4).
 *
 * <p>Signatures supposées : {@code new ListerCoursesPubliques(DepotCourses, DepotLogos)} ;
 * {@code CoursesPubliques executer()} ; record imbriqué
 * {@code ListerCoursesPubliques.CoursesPubliques(List<CourseListee> enCours, List<CourseListee> aVenir,
 * List<CourseListee> terminees)}.
 */
class ListerCoursesPubliquesTest {

    private static final UUID ID_A = id(1);
    private static final UUID ID_B = id(2);
    private static final UUID ID_C = id(3);
    private static final UUID ID_D = id(4);
    private static final UUID ID_E = id(5);
    private static final UUID ID_F = id(6);
    private static final UUID ID_G = id(7);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final ListerCoursesPubliques listerCoursesPubliques = new ListerCoursesPubliques(depotCourses, depotLogos);

    @Test
    @DisplayName("CA1 - A à G : enCours = [E, D], terminees = [G, F], aVenir = [B, C, A]")
    void doit_regrouper_les_courses_par_statut_et_les_trier_dans_chaque_rubrique() {
        enregistrerLesSeptCoursesDeReference();

        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        assertThat(ids(rubriques.enCours())).containsExactly(ID_E, ID_D);
        assertThat(ids(rubriques.terminees())).containsExactly(ID_G, ID_F);
        assertThat(ids(rubriques.aVenir())).containsExactly(ID_B, ID_C, ID_A);
    }

    @Test
    @DisplayName("CA1 - chaque Course figure dans une seule rubrique")
    void doit_placer_chaque_course_dans_exactement_une_rubrique() {
        enregistrerLesSeptCoursesDeReference();

        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        List<UUID> toutes = new java.util.ArrayList<>();
        toutes.addAll(ids(rubriques.enCours()));
        toutes.addAll(ids(rubriques.aVenir()));
        toutes.addAll(ids(rubriques.terminees()));
        assertThat(toutes).containsExactlyInAnyOrder(ID_A, ID_B, ID_C, ID_D, ID_E, ID_F, ID_G);
    }

    @Test
    @DisplayName("CA2 - aVenir : date croissante, puis nom sans casse (backyard beta avant Backyard Gamma)")
    void doit_ordonner_a_venir_par_date_croissante_puis_nom_sans_tenir_compte_de_la_casse() {
        depotCourses.enregistrer(course(ID_C, "Backyard Gamma", "2026-11-05", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_A, "Backyard Alpha", "2026-11-20", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_B, "backyard beta", "2026-11-05", StatutCourse.EN_PREPARATION));

        assertThat(ids(listerCoursesPubliques.executer().aVenir())).containsExactly(ID_B, ID_C, ID_A);
    }

    @Test
    @DisplayName("CA2 - aVenir : même date et même nom, départage par identifiant ; deux appels, même ordre")
    void doit_departager_par_identifiant_et_donner_le_meme_ordre_a_chaque_appel() {
        UUID petitId = id(10);
        UUID grandId = id(20);
        depotCourses.enregistrer(course(grandId, "Backyard Gamma", "2026-11-05", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_C, "Backyard Gamma", "2026-11-05", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(petitId, "Backyard Gamma", "2026-11-05", StatutCourse.EN_PREPARATION));

        List<UUID> premier = ids(listerCoursesPubliques.executer().aVenir());
        List<UUID> second = ids(listerCoursesPubliques.executer().aVenir());

        assertThat(premier).containsExactly(ID_C, petitId, grandId);
        assertThat(second).isEqualTo(premier);
    }

    @Test
    @DisplayName("CA2 - enCours et terminees : date décroissante (la plus récente d'abord)")
    void doit_ordonner_en_cours_et_terminees_par_date_decroissante() {
        depotCourses.enregistrer(course(ID_D, "Backyard Delta", "2026-10-20", StatutCourse.EN_COURS));
        depotCourses.enregistrer(course(ID_E, "Backyard Epsilon", "2026-10-21", StatutCourse.EN_COURS));
        depotCourses.enregistrer(course(ID_F, "Backyard Zeta", "2026-09-01", StatutCourse.TERMINEE));
        depotCourses.enregistrer(course(ID_G, "Backyard Eta", "2026-10-10", StatutCourse.TERMINEE));

        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        assertThat(ids(rubriques.enCours())).containsExactly(ID_E, ID_D);
        assertThat(ids(rubriques.terminees())).containsExactly(ID_G, ID_F);
    }

    @Test
    @DisplayName("CA3 - aucune Course : trois listes vides, sans exception")
    void doit_renvoyer_trois_rubriques_vides_quand_il_n_y_a_aucune_course() {
        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        assertThat(rubriques.enCours()).isNotNull().isEmpty();
        assertThat(rubriques.aVenir()).isNotNull().isEmpty();
        assertThat(rubriques.terminees()).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("CA3 - seulement des Courses TERMINEE : enCours et aVenir sont des listes vides")
    void doit_renvoyer_des_listes_vides_pour_les_rubriques_sans_course() {
        depotCourses.enregistrer(course(ID_F, "Backyard Zeta", "2026-09-01", StatutCourse.TERMINEE));
        depotCourses.enregistrer(course(ID_G, "Backyard Eta", "2026-10-10", StatutCourse.TERMINEE));

        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        assertThat(rubriques.enCours()).isNotNull().isEmpty();
        assertThat(rubriques.aVenir()).isNotNull().isEmpty();
        assertThat(ids(rubriques.terminees())).containsExactly(ID_G, ID_F);
    }

    @Test
    @DisplayName("CA4 - l'empreinte de logo est lue en un seul appel groupé, sans lire les octets")
    void doit_lire_les_empreintes_de_logo_en_un_seul_appel_groupe() {
        enregistrerLesSeptCoursesDeReference();
        AtomicInteger lecturesGroupees = new AtomicInteger();
        AtomicInteger lecturesIndividuelles = new AtomicInteger();
        DepotLogosDeTest delegue = depotLogos;
        delegue.enregistrer(ID_E, Logo.depuis(png()));
        DepotLogos compteur = new DepotLogos() {
            @Override
            public void enregistrer(UUID idCourse, Logo logo) {
                delegue.enregistrer(idCourse, logo);
            }

            @Override
            public Optional<Logo> parIdCourse(UUID idCourse) {
                throw new AssertionError("Les octets du logo ne doivent pas être lus");
            }

            @Override
            public void supprimer(UUID idCourse) {
                delegue.supprimer(idCourse);
            }

            @Override
            public Map<UUID, String> empreintesParIdCourse() {
                lecturesGroupees.incrementAndGet();
                return delegue.empreintesParIdCourse();
            }

            @Override
            public Optional<String> empreinteParIdCourse(UUID idCourse) {
                lecturesIndividuelles.incrementAndGet();
                return delegue.empreinteParIdCourse(idCourse);
            }
        };

        ListerCoursesPubliques.CoursesPubliques rubriques =
                new ListerCoursesPubliques(depotCourses, compteur).executer();

        assertThat(lecturesGroupees).hasValue(1);
        assertThat(lecturesIndividuelles).hasValue(0);
        assertThat(rubriques.enCours().get(0).empreinteLogo()).contains(Logo.depuis(png()).empreinte());
        assertThat(rubriques.enCours().get(1).empreinteLogo()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - une seule lecture de Courses, aucune lecture individuelle ni verrouillée")
    void doit_lire_les_courses_en_une_seule_fois() {
        enregistrerLesSeptCoursesDeReference();

        listerCoursesPubliques.executer();

        assertThat(depotCourses.nombreDeChargementsParId).isZero();
        assertThat(depotCourses.nombreDeChargementsPourModification).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(7);
    }

    @Test
    @DisplayName("CA4 - ni Inscriptions, ni Passages, ni annuaire de pseudos, ni Clock parmi les dépendances")
    void ne_doit_dependre_ni_des_inscriptions_ni_des_passages_ni_des_pseudos_ni_de_l_horloge() {
        Constructor<?>[] constructeurs = ListerCoursesPubliques.class.getConstructors();

        assertThat(constructeurs).hasSize(1);
        List<String> dependances = Arrays.stream(constructeurs[0].getParameterTypes()).map(Class::getSimpleName)
                .toList();
        assertThat(dependances).doesNotContain("DepotInscriptions", "DepotPassages", "AnnuairePseudos", "Clock");
    }

    @Test
    @DisplayName("CA4 - une Course EN_COURS sans heure de départ est dans enCours")
    void doit_lister_en_cours_une_course_en_cours_sans_heure_de_depart() {
        depotCourses.enregistrer(Course.reconstituer(ID_D, "Backyard Delta", LocalDate.parse("2026-10-20"),
                StatutCourse.EN_COURS, new ParametresBoucle(6706, 60, 120), 50, 24, List.of(), null));

        ListerCoursesPubliques.CoursesPubliques rubriques = listerCoursesPubliques.executer();

        assertThat(ids(rubriques.enCours())).containsExactly(ID_D);
        assertThat(rubriques.aVenir()).isEmpty();
        assertThat(rubriques.terminees()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - le statut enregistré fait foi, même si l'heure de départ est très ancienne")
    void doit_se_fier_au_statut_enregistre_sans_calcul_temporel() {
        depotCourses.enregistrer(Course.reconstituer(ID_D, "Backyard Delta", LocalDate.parse("2020-01-01"),
                StatutCourse.EN_COURS, new ParametresBoucle(6706, 60, 120), 50, 24, List.of(),
                Instant.parse("2020-01-01T08:00:00Z")));

        assertThat(ids(listerCoursesPubliques.executer().enCours())).containsExactly(ID_D);
    }

    private void enregistrerLesSeptCoursesDeReference() {
        depotCourses.enregistrer(course(ID_A, "Backyard Alpha", "2026-11-20", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_B, "backyard beta", "2026-11-05", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_C, "Backyard Gamma", "2026-11-05", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_D, "Backyard Delta", "2026-10-20", StatutCourse.EN_COURS));
        depotCourses.enregistrer(course(ID_E, "Backyard Epsilon", "2026-10-21", StatutCourse.EN_COURS));
        depotCourses.enregistrer(course(ID_F, "Backyard Zeta", "2026-09-01", StatutCourse.TERMINEE));
        depotCourses.enregistrer(course(ID_G, "Backyard Eta", "2026-10-10", StatutCourse.TERMINEE));
    }

    private static Course course(UUID id, String nom, String date, StatutCourse statut) {
        return DepotCoursesDeTest.course(id, nom, LocalDate.parse(date), statut);
    }

    private static List<UUID> ids(List<CourseListee> courses) {
        return courses.stream().map(c -> c.course().id()).toList();
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
