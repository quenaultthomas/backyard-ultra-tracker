package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ListerCoursesOuvertes.CourseOuverte;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.2, CA21 (RG10) : l'indicateur « complète » vient d'un comptage groupé
 * {@code DepotInscriptions.nombresInscritsParCourse(Collection<UUID>)} (Map, 0 pour une Course sans Inscrit),
 * une seule fois pour toute la liste.
 */
class ListerCoursesOuvertesGroupeTest {

    private static final UUID COMPTE = UUID.fromString("00000000-0000-0000-0000-0000000000c9");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final ListerCoursesOuvertes lister = new ListerCoursesOuvertes(depotCourses, depotInscriptions);

    @Test
    @DisplayName("CA21 - Course de 10 places : complète avec 10 Inscrits, non complète avec 9, non complète avec 0")
    void doit_calculer_complete_pour_dix_neuf_et_zero_inscrits() {
        UUID pleine = id(1);
        UUID presquePleine = id(2);
        UUID vide = id(3);
        depotCourses.enregistrer(course(pleine));
        depotCourses.enregistrer(course(presquePleine));
        depotCourses.enregistrer(course(vide));
        inscrire(pleine, 10);
        inscrire(presquePleine, 9);

        List<CourseOuverte> resultat = lister.executer(COMPTE);

        assertThat(complete(resultat, pleine)).isTrue();
        assertThat(complete(resultat, presquePleine)).isFalse();
        assertThat(complete(resultat, vide)).isFalse();
    }

    @Test
    @DisplayName("CA21 - un seul comptage groupé pour toute la liste, aucun comptage Course par Course")
    void doit_compter_les_inscrits_en_une_seule_requete_groupee() {
        depotCourses.enregistrer(course(id(1)));
        depotCourses.enregistrer(course(id(2)));
        depotCourses.enregistrer(course(id(3)));
        inscrire(id(1), 2);

        lister.executer(COMPTE);

        assertThat(depotInscriptions.nombreDeComptagesGroupes).isEqualTo(1);
        assertThat(depotInscriptions.nombreDeComptagesUnitaires).isZero();
    }

    @Test
    @DisplayName("CA21 - le port renvoie 0 pour une Course sans Inscrit et le compte exact pour les autres")
    void doit_renvoyer_zero_pour_une_course_sans_inscrit_et_le_compte_exact_sinon() {
        inscrire(id(1), 4);

        var nombres = depotInscriptions.nombresInscritsParCourse(List.of(id(1), id(2)));

        assertThat(nombres).containsEntry(id(1), 4).containsEntry(id(2), 0);
    }

    private void inscrire(UUID courseId, int nombre) {
        for (int dossard = 1; dossard <= nombre; dossard++) {
            depotInscriptions.enregistrer(Inscription.reconstituer(UUID.randomUUID(), courseId, UUID.randomUUID(),
                    dossard, new JetonQr(String.format("%043d", dossard)), StatutInscription.EN_COURSE));
        }
    }

    private static boolean complete(List<CourseOuverte> resultat, UUID courseId) {
        return resultat.stream().filter(c -> c.course().id().equals(courseId)).findFirst().orElseThrow().complete();
    }

    private static Course course(UUID id) {
        return Course.reconstituer(id, "Course " + id, LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION,
                new ParametresBoucle(6706, 60, 120), 10, 24);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
