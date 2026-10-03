package fr.backyard.tracker.courses.application;

import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.webp;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonModifiableException;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoInvalideException;
import fr.backyard.tracker.courses.domaine.LogoInvalideException.Motif;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.3 : cas d'usage EnregistrerLogo (CA5). Pas d'horloge : aucune règle temporelle. */
class EnregistrerLogoTest {

    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final byte[] OCTETS_INVALIDES = ascii("pas une image");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotLogosDeTest depotLogos = new DepotLogosDeTest();
    private final EnregistrerLogo enregistrerLogo = new EnregistrerLogo(depotCourses, depotLogos);

    @Test
    @DisplayName("CA5 - remplace le logo PNG de A par un WebP : un seul logo pour A, B intacte, empreinte du nouveau logo exposée")
    void doit_remplacer_le_logo_de_la_course_sans_toucher_aux_autres() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(ID_B, StatutCourse.EN_PREPARATION));
        depotLogos.enregistrer(ID_A, Logo.depuis(png()));
        Logo logoDeB = Logo.depuis(jpeg());
        depotLogos.enregistrer(ID_B, logoDeB);
        Logo nouveau = Logo.depuis(webp());

        EnregistrerLogo.Resultat resultat = enregistrerLogo.executer(ID_A, webp());

        assertThat(resultat.empreinte()).isEqualTo(nouveau.empreinte());
        assertThat(resultat.course().id()).isEqualTo(ID_A);
        assertThat(depotLogos.parIdCourse(ID_A)).contains(nouveau);
        assertThat(depotLogos.parIdCourse(ID_B)).contains(logoDeB);
        assertThat(depotLogos.nombreDeLogos()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA5 - une Course sans logo en reçoit un")
    void doit_creer_le_logo_d_une_course_qui_n_en_avait_pas() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));

        EnregistrerLogo.Resultat resultat = enregistrerLogo.executer(ID_A, png());

        assertThat(depotLogos.parIdCourse(ID_A)).contains(Logo.depuis(png()));
        assertThat(resultat.empreinte()).isEqualTo(Logo.depuis(png()).empreinte());
        assertThat(depotLogos.nombreDeLogos()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - envoyer deux fois le même fichier donne le même état et la même empreinte (idempotence)")
    void doit_etre_idempotent_pour_le_meme_fichier() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));

        EnregistrerLogo.Resultat premier = enregistrerLogo.executer(ID_A, png());
        EnregistrerLogo.Resultat second = enregistrerLogo.executer(ID_A, png());

        assertThat(second.empreinte()).isEqualTo(premier.empreinte());
        assertThat(depotLogos.nombreDeLogos()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - id inconnu : CourseIntrouvableException, dépôts inchangés")
    void doit_lever_course_introuvable_pour_un_id_inconnu() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));

        assertThatThrownBy(() -> enregistrerLogo.executer(ID_INCONNU, png()))
                .isInstanceOf(CourseIntrouvableException.class);

        assertDepotsInchanges();
    }

    @Test
    @DisplayName("CA5 - id inconnu avec des octets invalides : 404 prime (CourseIntrouvableException)")
    void doit_privilegier_course_introuvable_sur_des_octets_invalides() {
        assertThatThrownBy(() -> enregistrerLogo.executer(ID_INCONNU, OCTETS_INVALIDES))
                .isInstanceOf(CourseIntrouvableException.class);

        assertDepotsInchanges();
    }

    @Test
    @DisplayName("CA5 - Course EN_COURS : CourseNonModifiableException, logo existant conservé")
    void doit_lever_course_non_modifiable_pour_une_course_en_cours() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_COURS));
        Logo ancien = Logo.depuis(png());
        depotLogos.enregistrer(ID_A, ancien);

        assertThatThrownBy(() -> enregistrerLogo.executer(ID_A, webp()))
                .isInstanceOf(CourseNonModifiableException.class);

        assertThat(depotLogos.parIdCourse(ID_A)).contains(ancien);
        assertThat(depotLogos.nombreDEcritures).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - Course TERMINEE avec des octets invalides : le statut prime (CourseNonModifiableException)")
    void doit_privilegier_le_statut_sur_des_octets_invalides() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.TERMINEE));

        assertThatThrownBy(() -> enregistrerLogo.executer(ID_A, OCTETS_INVALIDES))
                .isInstanceOf(CourseNonModifiableException.class);

        assertThat(depotLogos.nombreDeLogos()).isZero();
        assertThat(depotLogos.nombreDEcritures).isZero();
    }

    @Test
    @DisplayName("CA5 - octets invalides sur A : LogoInvalideException FORMAT_INVALIDE, ancien logo conservé")
    void doit_conserver_l_ancien_logo_quand_les_octets_sont_invalides() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        Logo ancien = Logo.depuis(png());
        depotLogos.enregistrer(ID_A, ancien);

        assertThatThrownBy(() -> enregistrerLogo.executer(ID_A, OCTETS_INVALIDES))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.FORMAT_INVALIDE));

        assertThat(depotLogos.parIdCourse(ID_A)).contains(ancien);
        assertThat(depotLogos.nombreDEcritures).isEqualTo(1);
    }

    @Test
    @DisplayName("CA5 - octets absents ou vides sur A : LogoInvalideException REQUIS, ancien logo conservé")
    void doit_refuser_un_fichier_vide_et_conserver_l_ancien_logo() {
        depotCourses.enregistrer(course(ID_A, StatutCourse.EN_PREPARATION));
        Logo ancien = Logo.depuis(png());
        depotLogos.enregistrer(ID_A, ancien);

        assertThatThrownBy(() -> enregistrerLogo.executer(ID_A, new byte[0]))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.REQUIS));
        assertThatThrownBy(() -> enregistrerLogo.executer(ID_A, null))
                .isInstanceOfSatisfying(LogoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.REQUIS));

        assertThat(depotLogos.parIdCourse(ID_A)).contains(ancien);
    }

    @Test
    @DisplayName("CA5 - l'enregistrement d'un logo ne modifie pas la Course elle-même (RG13)")
    void doit_ne_pas_reenregistrer_la_course() {
        Course a = course(ID_A, StatutCourse.EN_PREPARATION);
        depotCourses.enregistrer(a);

        enregistrerLogo.executer(ID_A, png());

        assertThat(depotCourses.parId(ID_A)).containsSame(a);
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(1);
    }

    private void assertDepotsInchanges() {
        assertThat(depotLogos.nombreDeLogos()).isZero();
        assertThat(depotLogos.nombreDEcritures).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(depotCourses.toutes().size());
    }

    private static Course course(UUID id, StatutCourse statut) {
        return DepotCoursesDeTest.course(id, "Course " + id, LocalDate.of(2026, 11, 14), statut);
    }
}
