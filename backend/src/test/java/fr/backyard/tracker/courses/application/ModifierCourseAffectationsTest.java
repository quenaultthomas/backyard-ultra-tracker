package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.application.ModifierCourse.Commande;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 2.4 : ModifierCourse ne touche pas aux affectations (CA6 ; RG7). */
class ModifierCourseAffectationsTest {

    private static final UUID L1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID L2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID ID_A = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID ID_B = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

    private final DepotCoursesAvecAffectationsDeTest depot = new DepotCoursesAvecAffectationsDeTest();
    private final ModifierCourse modifierCourse = new ModifierCourse(depot,
            Clock.fixed(Instant.parse("2026-10-03T10:00:00Z"), ZoneOffset.UTC));

    @Test
    @DisplayName("CA6 - modifier A avec bénévoles laisse ses affectations intactes dans le dépôt, B intacte")
    void doit_laisser_les_affectations_intactes_apres_modification_de_la_course() {
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_A, "Backyard des Crêtes",
                LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION, Set.of(L1, L2)));
        depot.enregistrer(DepotCoursesAvecAffectationsDeTest.course(ID_B, "Piste plate",
                LocalDate.of(2026, 12, 1), StatutCourse.EN_PREPARATION, Set.of(L1)));

        Course modifiee = modifierCourse.executer(new Commande(ID_A, "Backyard des Alpes",
                LocalDate.of(2026, 12, 5), 8000, 45, 0, 80, 12));

        assertThat(modifiee.benevolesAffectes()).containsExactlyInAnyOrder(L1, L2);
        Course enregistree = depot.parId(ID_A).orElseThrow();
        assertThat(enregistree.nom()).isEqualTo("Backyard des Alpes");
        assertThat(enregistree.benevolesAffectes()).containsExactlyInAnyOrder(L1, L2);
        assertThat(depot.parId(ID_B).orElseThrow().benevolesAffectes()).containsExactly(L1);
    }
}
