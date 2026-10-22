package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Décor de l'incrément 7.1 : Course de Boucles de 60 min (grâce de 3 min), départ 10:00:00Z, bénévole Léo affecté,
 * dépôts en mémoire, coureurs A et B EN_COURSE. Un Passage « sur la Boucle N » est un SCAN fait en N.
 */
final class DecorGraceDeTest {

    static final Instant DEPART = Instant.parse("2026-10-21T10:00:00Z");
    static final LocalDate JOUR = LocalDate.of(2026, 10, 21);
    static final UUID COURSE = ScenarioPassageDeTest.id(0x71);
    static final UUID LEO = ScenarioPassageDeTest.id(0xa1);

    final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    final ScenarioPassageDeTest.AnnuaireDeTest annuaire = new ScenarioPassageDeTest.AnnuaireDeTest();

    DecorGraceDeTest(int nombreMaxBoucles) {
        courses.enregistrer(Course.reconstituer(COURSE, "Backyard du jour", JOUR, StatutCourse.EN_COURS,
                new ParametresBoucle(400, 60, 5), 10, nombreMaxBoucles, Set.of(LEO), DEPART));
    }

    static Instant a(String heure) {
        return Instant.parse("2026-10-21T" + heure + "Z");
    }

    Inscription coureur(int dossard, int... boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), COURSE, UUID.randomUUID(), dossard,
                JetonQr.generer(), StatutInscription.EN_COURSE);
        inscriptions.enregistrer(inscription);
        for (int boucle : boucles) {
            passages.poser(Passage.scanner(UUID.randomUUID(), inscription, boucle,
                    DEPART.plusSeconds(3600L * (boucle - 1) + 600)));
        }
        return inscription;
    }

    AbandonnerCoureursHorsDelai controle(Instant maintenant) {
        return new AbandonnerCoureursHorsDelai(courses, inscriptions, passages,
                Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    EnregistrerPassage scan(Instant maintenant) {
        return new EnregistrerPassage(courses, inscriptions, passages, annuaire,
                Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    Inscription relue(Inscription inscription) {
        List<Inscription> trouvees = inscriptions.inscriptions.stream()
                .filter(i -> i.id().equals(inscription.id())).toList();
        return trouvees.get(0);
    }

    StatutCourse statutCourse() {
        return courses.parId(COURSE).orElseThrow().statut();
    }
}
