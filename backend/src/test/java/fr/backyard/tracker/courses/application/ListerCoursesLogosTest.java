package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.3 : ListerCourses expose l'empreinte du logo de chaque Course (CA7, RG12). */
class ListerCoursesLogosTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final ListerCourses listerCourses = new ListerCourses(depotCourses, depotLogos);

    @Test
    @DisplayName("CA7 - A avec logo : son empreinte est donnée ; B sans logo : aucune empreinte")
    void doit_donner_l_empreinte_du_logo_pour_la_course_qui_en_a_un() {
        depotCourses.enregistrer(course(ID_A, "Alpha", LocalDate.of(2026, 12, 1)));
        depotCourses.enregistrer(course(ID_B, "Beta", LocalDate.of(2026, 12, 1)));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));
        depotLogos.interdireLaLectureDesOctets();

        List<ListerCourses.CourseListee> liste = listerCourses.executerAvecLogos();

        assertThat(liste).hasSize(2);
        assertThat(liste.get(0).course().id()).isEqualTo(ID_A);
        assertThat(liste.get(0).empreinteLogo()).contains(Logo.depuis(png()).empreinte());
        assertThat(liste.get(1).course().id()).isEqualTo(ID_B);
        assertThat(liste.get(1).empreinteLogo()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - l'ordre de tri est inchangé (date décroissante, nom, id) même avec des logos")
    void doit_conserver_l_ordre_de_tri_de_la_liste() {
        Course ancienne = course(ID_A, "Alpha", LocalDate.of(2026, 11, 1));
        Course recente = course(ID_B, "Beta", LocalDate.of(2027, 1, 10));
        depotCourses.enregistrer(ancienne);
        depotCourses.enregistrer(recente);
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));

        List<ListerCourses.CourseListee> liste = listerCourses.executerAvecLogos();

        assertThat(liste).extracting(c -> c.course().id()).containsExactly(ID_B, ID_A);
        assertThat(liste.get(0).empreinteLogo()).isEmpty();
        assertThat(liste.get(1).empreinteLogo()).isPresent();
    }

    @Test
    @DisplayName("CA7 - sans lire un seul octet de logo : le double du port ne répond qu'aux empreintes")
    void doit_lister_sans_lire_les_octets_des_logos() {
        depotCourses.enregistrer(course(ID_A, "Alpha", LocalDate.of(2026, 12, 1)));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));
        depotLogos.interdireLaLectureDesOctets();

        List<ListerCourses.CourseListee> liste = listerCourses.executerAvecLogos();

        assertThat(liste).hasSize(1);
    }

    @Test
    @DisplayName("CA7 - dépôt vide : liste vide")
    void doit_renvoyer_une_liste_vide_quand_il_n_y_a_aucune_course() {
        assertThat(listerCourses.executerAvecLogos()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - le logo d'une Course EN_COURS ou TERMINEE reste exposé")
    void doit_exposer_l_empreinte_quel_que_soit_le_statut() {
        depotCourses.enregistrer(DepotCoursesDeTest.course(ID_A, "Alpha", LocalDate.of(2026, 12, 1), StatutCourse.EN_COURS));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));

        assertThat(listerCourses.executerAvecLogos().get(0).empreinteLogo()).isPresent();
    }

    private static Course course(UUID id, String nom, LocalDate date) {
        return DepotCoursesDeTest.course(id, nom, date, StatutCourse.EN_PREPARATION);
    }
}
