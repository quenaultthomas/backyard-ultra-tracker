package fr.backyard.tracker.courses.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests de l'incrément 3.5 : places restantes d'une Course (CA2 ; RG2). */
class CoursePlacesRestantesTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000c5");

    @ParameterizedTest(name = "CA2 - max 50, {0} inscrit(s) : {1} place(s) restante(s), complète = {2}")
    @CsvSource({"0,50,false", "1,49,false", "49,1,false", "50,0,true", "52,0,true"})
    void doit_calculer_les_places_restantes_sans_jamais_descendre_sous_zero(int inscrits, int attendues,
                                                                          boolean complete) {
        Course course = course(50);

        assertThat(course.placesRestantes(inscrits)).isEqualTo(attendues);
        assertThat(course.estComplete(inscrits)).isEqualTo(complete);
    }

    private static Course course(int nombreMaxParticipants) {
        return Course.reconstituer(ID, "Backyard de démo", LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION,
                new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }
}
