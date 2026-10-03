package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.3 : cas d'usage SupprimerLogo (CA6). */
class SupprimerLogoTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final SupprimerLogo supprimerLogo = new SupprimerLogo(depotCourses, depotLogos);

    @Test
    @DisplayName("CA6 - supprime le logo de A : A n'a plus de logo, B est intacte")
    void doit_supprimer_le_logo_de_la_course_sans_toucher_aux_autres() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_B, StatutCourse.EN_PREPARATION));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));
        Logo logoDeB = Logo.depuis(jpeg());
        depotLogos.enregistrer(ID_B, logoDeB);

        supprimerLogo.executer(ID_A);

        assertThat(depotLogos.parIdCourse(ID_A)).isEmpty();
        assertThat(depotLogos.parIdCourse(ID_B)).contains(logoDeB);
    }

    @Test
    @DisplayName("CA6 - Course EN_PREPARATION sans logo : aucune erreur (idempotent), rien ne change")
    void doit_etre_idempotent_pour_une_course_sans_logo() {
        depotCourses.enregistrer(course(ID_C, StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_B, StatutCourse.EN_PREPARATION));
        Logo logoDeB = Logo.depuis(jpeg());
        depotLogos.enregistrer(ID_B, logoDeB);

        assertThatCode(() -> supprimerLogo.executer(ID_C)).doesNotThrowAnyException();

        assertThat(depotLogos.parIdCourse(ID_C)).isEmpty();
        assertThat(depotLogos.parIdCourse(ID_B)).contains(logoDeB);
    }

    @Test
    @DisplayName("CA6 - supprimer deux fois le logo de A : aucune erreur la seconde fois")
    void doit_accepter_une_seconde_suppression() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));

        supprimerLogo.executer(ID_A);

        assertThatCode(() -> supprimerLogo.executer(ID_A)).doesNotThrowAnyException();
        assertThat(depotLogos.parIdCourse(ID_A)).isEmpty();
    }

    @Test
    @DisplayName("CA6 - id inconnu : CourseIntrouvableException, logos inchangés")
    void doit_lever_course_introuvable_pour_un_id_inconnu() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));

        assertThatThrownBy(() -> supprimerLogo.executer(ID_INCONNU)).isInstanceOf(CourseIntrouvableException.class);

        assertThat(depotLogos.nombreDeLogos()).isEqualTo(1);
        assertThat(depotLogos.nombreDEcritures).isEqualTo(1);
    }

    @Test
    @DisplayName("CA6 - Course EN_COURS avec logo : CourseNonModifiableException et logo conservé")
    void doit_refuser_la_suppression_d_un_logo_sur_une_course_en_cours() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_COURS));
        Logo logo = Logo.depuis(png());
        depotLogos.enregistrer(ID_A, logo);

        assertThatThrownBy(() -> supprimerLogo.executer(ID_A)).isInstanceOf(CourseNonModifiableException.class);

        assertThat(depotLogos.parIdCourse(ID_A)).contains(logo);
    }

    @Test
    @DisplayName("CA6 - Course EN_COURS sans logo : CourseNonModifiableException (le statut prime sur l'idempotence)")
    void doit_refuser_la_suppression_sur_une_course_en_cours_meme_sans_logo() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_COURS));

        assertThatThrownBy(() -> supprimerLogo.executer(ID_A)).isInstanceOf(CourseNonModifiableException.class);
    }

    @Test
    @DisplayName("CA6 - Course TERMINEE avec logo : CourseNonModifiableException et logo conservé")
    void doit_refuser_la_suppression_d_un_logo_sur_une_course_terminee() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.TERMINEE));
        Logo logo = Logo.depuis(png());
        depotLogos.enregistrer(ID_A, logo);

        assertThatThrownBy(() -> supprimerLogo.executer(ID_A)).isInstanceOf(CourseNonModifiableException.class);

        assertThat(depotLogos.parIdCourse(ID_A)).contains(logo);
    }

    private static Course course(UUID id, StatutCourse statut) {
        return DepotCoursesDeTest.course(id, "Course " + id, LocalDate.of(2026, 11, 14), statut);
    }
}
