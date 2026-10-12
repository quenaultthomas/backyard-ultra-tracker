package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Décor commun des tests unitaires de EnregistrerPassage (4.4a) : valeurs de référence de la spec. Course X
 * (Backyard des Crêtes, boucles de 1 min, M = 5, bénévole Léo, t0 = 14:03:27Z), Inscriptions Bruno (dossard 1) et
 * Chloé (dossard 2) EN_COURSE. Y EN_PREPARATION, Z TERMINEE, U EN_COURS sans heure de départ et W (EN_COURS, boucles
 * de 2 min, départ à t0 + 30 s) ont aussi Léo pour bénévole. Marc n'est affecté à aucune Course.
 *
 * Signature attendue du cas d'usage : {@code new EnregistrerPassage(DepotCourses, DepotInscriptions, DepotPassages,
 * AnnuairePseudos, Clock)} ; {@code EnregistrerPassage.Resultat executer(UUID courseId, UUID benevoleId, JetonQr
 * jeton, UUID identifiantScan, long ageDuScanMillisecondes)} ; record imbriqué
 * {@code Resultat(Passage passage, int dossard, String pseudo, boolean rejeu)}.
 */
final class ScenarioPassageDeTest {

    static final Instant T0 = Instant.parse("2026-10-12T14:03:27Z");
    static final LocalDate JOUR = LocalDate.of(2026, 10, 12);

    static final UUID X = id(1);
    static final UUID Y = id(2);
    static final UUID Z = id(3);
    static final UUID U = id(4);
    static final UUID W = id(5);
    static final UUID INCONNUE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    static final UUID LEO = id(0xa1);
    static final UUID MARC = id(0xa2);
    static final UUID COMPTE_BRUNO = id(0xb1);
    static final UUID COMPTE_CHLOE = id(0xb2);

    final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    final AnnuaireDeTest annuaire = new AnnuaireDeTest();

    final Inscription bruno;
    final Inscription chloe;

    ScenarioPassageDeTest() {
        courses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        courses.enregistrer(course(Y, StatutCourse.EN_PREPARATION, 1, null));
        courses.enregistrer(course(Z, StatutCourse.TERMINEE, 1, T0));
        courses.enregistrer(course(U, StatutCourse.EN_COURS, 1, null));
        courses.enregistrer(course(W, StatutCourse.EN_COURS, 2, T0.plusSeconds(30)));
        courses.nombreDEnregistrements = 0;
        bruno = inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        chloe = inscrire(X, COMPTE_CHLOE, 2, StatutInscription.EN_COURSE);
        annuaire.pseudos.put(COMPTE_BRUNO, "Bruno");
        annuaire.pseudos.put(COMPTE_CHLOE, "Chloé");
    }

    // ---------- cas d'usage ----------

    EnregistrerPassage casA(Instant maintenant) {
        return casAvec(Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    EnregistrerPassage casAvec(Clock clock) {
        return new EnregistrerPassage(courses, inscriptions, passages, annuaire, clock);
    }

    /** Léo scanne le coureur sur la Course X, en direct, avec un nouvel identifiant de scan. */
    EnregistrerPassage.Resultat scannerSurX(Instant maintenant, Inscription coureur) {
        return casA(maintenant).executer(X, LEO, coureur.jetonQr(), UUID.randomUUID(), 0);
    }

    // ---------- fabriques nommées métier ----------

    static Course course(UUID id, StatutCourse statut, int dureeBoucleMinutes, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard des Crêtes " + id, JOUR, statut,
                new ParametresBoucle(400, dureeBoucleMinutes, 5), 10, 5, Set.of(LEO), demarreeLe);
    }

    Inscription inscrire(UUID courseId, UUID compteId, int dossard, StatutInscription statut) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut);
        inscriptions.enregistrer(inscription);
        return inscription;
    }

    /** Remplace l'Inscription du dépôt par la même avec un autre statut (changement survenu entre-temps). */
    Inscription avecStatut(Inscription inscription, StatutInscription statut) {
        Inscription modifiee = Inscription.reconstituer(inscription.id(), inscription.courseId(),
                inscription.compteId(), inscription.dossard(), inscription.jetonQr(), statut);
        inscriptions.inscriptions.replaceAll(i -> i.id().equals(inscription.id()) ? modifiee : i);
        return modifiee;
    }

    void remplacerCourse(Course course) {
        courses.enregistrer(course);
        courses.nombreDEnregistrements = 0;
    }

    /** Passage SCAN préexistant, posé sans compter d'écriture. */
    Passage passeSur(Inscription inscription, int boucle) {
        Passage passage = Passage.scanner(UUID.randomUUID(), inscription, boucle, T0.plusSeconds(60L * (boucle - 1)));
        passages.poser(passage);
        return passage;
    }

    /** Passage CORRECTION préexistant (réintégration, 4.12), posé sans compter d'écriture. */
    Passage corrigeSur(Inscription inscription, int boucle) {
        Passage passage = Passage.corriger(UUID.randomUUID(), inscription, boucle);
        passages.poser(passage);
        return passage;
    }

    // ---------- vérifications ----------

    /** Le scan lève l'exception attendue et n'écrit rien : ni Passage, ni Course, ni Inscription. */
    void assertRefusSansEcriture(ThrowingCallable scan, Class<? extends Throwable> exception) {
        List<Passage> passagesAvant = List.copyOf(passages.passages);
        List<Inscription> inscriptionsAvant = List.copyOf(inscriptions.inscriptions);
        List<StatutInscription> statutsAvant = inscriptions.inscriptions.stream().map(Inscription::statut).toList();
        int ecrituresPassagesAvant = passages.nombreDEnregistrements;

        assertThatThrownBy(scan).isInstanceOf(exception);

        assertThat(passages.passages).containsExactlyElementsOf(passagesAvant);
        assertThat(passages.nombreDEnregistrements).isEqualTo(ecrituresPassagesAvant);
        assertThat(courses.nombreDEnregistrements).isZero();
        assertThat(inscriptions.inscriptions).containsExactlyElementsOf(inscriptionsAvant);
        assertThat(inscriptions.inscriptions.stream().map(Inscription::statut).toList()).isEqualTo(statutsAvant);
    }

    static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }

    // ---------- doubles ----------

    static final class AnnuaireDeTest implements AnnuairePseudos {
        final Map<UUID, String> pseudos = new HashMap<>();

        @Override
        public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
            Map<UUID, String> trouves = new HashMap<>();
            idsComptes.stream().filter(pseudos::containsKey).forEach(id -> trouves.put(id, pseudos.get(id)));
            return trouves;
        }
    }

    /** Clock dont chaque lecture avance l'instant : une seconde lecture donnerait une valeur différente. */
    static final class ClockAvanceALaLecture extends Clock {
        final AtomicInteger lectures = new AtomicInteger();
        private final Instant depart;
        private final Duration pas;

        ClockAvanceALaLecture(Instant depart, Duration pas) {
            this.depart = depart;
            this.pas = pas;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return depart.plus(pas.multipliedBy(lectures.getAndIncrement()));
        }
    }
}
