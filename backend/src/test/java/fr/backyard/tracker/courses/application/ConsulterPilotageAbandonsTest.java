package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.8 : abandons dans ConsulterPilotage (CA4 ; RG15).
 *
 * Signatures supposées : {@code Pilotage} expose en plus {@code int nombreAbandons()} et
 * {@code List<InscritEnAbandon> inscritsEnAbandon()} (triés par dossard croissant) ; chaque
 * {@code InscritEnAbandon} expose {@code dossard()}, {@code pseudo()}, {@code statut()},
 * {@code int nombreBouclesTerminees()} (nombre de Passages), {@code Optional<Integer> boucleAbandon()} et
 * {@code Optional<MotifAbandon> motifAbandon()} (vides pour un abandon legacy sans motif ni boucle).
 */
class ConsulterPilotageAbandonsTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);
    private static final UUID Z = id(2);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest depotPassages = new DepotPassagesEnMemoireDeTest();
    private final Map<UUID, String> pseudos = new HashMap<>();
    private final AnnuairePseudos annuaire = ids -> {
        Map<UUID, String> trouves = new HashMap<>();
        ids.stream().filter(pseudos::containsKey).forEach(i -> trouves.put(i, pseudos.get(i)));
        return trouves;
    };

    private ConsulterPilotage consulterA(Instant maintenant) {
        return new ConsulterPilotage(depotCourses, depotInscriptions, depotPassages, annuaire,
                Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("CA4 - un abandon figure dans inscritsEnAbandon (boucle, motif, boucles terminées), absent de inscritsEnCourse et des compteurs")
    void doit_lister_les_abandons_a_part_des_inscrits_en_course() {
        garnir(X, StatutCourse.EN_COURS);
        Inscription bruno = inscrire(X, 1, "Bruno");
        Inscription chloe = inscrire(X, 2, "Chloé");
        Inscription tom = inscrire(X, 3, "Tom");
        Inscription david = inscrire(X, 4, "David");
        scan(bruno, 1);
        scan(tom, 1);
        scan(david, 1);
        remplacer(chloe.abandonner(MotifAbandon.HORS_DELAI, 1));
        remplacer(david.abandonner(MotifAbandon.MANUEL, 2));

        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.inscritsEnAbandon())
                .extracting(a -> a.dossard(), a -> a.pseudo(), a -> a.statut(), a -> a.nombreBouclesTerminees(),
                        a -> a.boucleAbandon(), a -> a.motifAbandon())
                .containsExactly(
                        tuple(2, "Chloé", StatutInscription.ABANDON, 0, Optional.of(1),
                                Optional.of(MotifAbandon.HORS_DELAI)),
                        tuple(4, "David", StatutInscription.ABANDON, 1, Optional.of(2),
                                Optional.of(MotifAbandon.MANUEL)));
        assertThat(pilotage.nombreAbandons()).isEqualTo(2);
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).containsExactly("Bruno", "Tom");
        assertThat(pilotage.nombreAyantBoucle().orElseThrow() + pilotage.nombreEnBoucle().orElseThrow()).isEqualTo(2);
        assertThat(pilotage.nombreEnBoucle()).contains(2);
        assertThat(pilotage.nombreInscrits()).isEqualTo(4);
    }

    @Test
    @DisplayName("CA4 - les abandons sont triés par dossard croissant")
    void doit_trier_les_abandons_par_dossard() {
        garnir(X, StatutCourse.EN_COURS);
        Inscription trois = inscrire(X, 3, "Tom");
        Inscription un = inscrire(X, 1, "Bruno");
        Inscription deux = inscrire(X, 2, "Chloé");
        remplacer(trois.abandonner(MotifAbandon.HORS_DELAI, 1));
        remplacer(un.abandonner(MotifAbandon.HORS_DELAI, 1));
        remplacer(deux.abandonner(MotifAbandon.HORS_DELAI, 1));

        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.inscritsEnAbandon()).extracting(a -> a.dossard()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("CA4 - abandon legacy (ABANDON sans motif ni boucle) : champs vides, aucune erreur")
    void doit_lister_un_abandon_legacy_sans_motif_ni_boucle() {
        garnir(X, StatutCourse.EN_COURS);
        inscrireAvecStatut(X, 1, "Bruno", StatutInscription.ABANDON);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.inscritsEnAbandon())
                .extracting(a -> a.boucleAbandon(), a -> a.motifAbandon())
                .containsExactly(tuple(Optional.empty(), Optional.empty()));
    }

    @Test
    @DisplayName("CA4 - Course TERMINEE : les abandons sont renseignés")
    void doit_renseigner_les_abandons_d_une_course_terminee() {
        garnir(Z, StatutCourse.TERMINEE);
        Inscription chloe = inscrire(Z, 2, "Chloé");
        remplacer(chloe.abandonner(MotifAbandon.HORS_DELAI, 3));

        var pilotage = consulterA(T0.plusSeconds(90)).executer(Z);

        assertThat(pilotage.nombreAbandons()).isEqualTo(1);
        assertThat(pilotage.inscritsEnAbandon()).extracting(a -> a.dossard(), a -> a.boucleAbandon())
                .containsExactly(tuple(2, Optional.of(3)));
    }

    @Test
    @DisplayName("CA4 - RG15 : sans abandon, la liste est vide et nombreAbandons vaut 0")
    void doit_renvoyer_une_liste_vide_sans_abandon() {
        garnir(X, StatutCourse.EN_COURS);
        inscrire(X, 1, "Bruno");

        var pilotage = consulterA(T0.plusSeconds(30)).executer(X);

        assertThat(pilotage.nombreAbandons()).isZero();
        assertThat(pilotage.inscritsEnAbandon()).isEmpty();
    }

    @Test
    @DisplayName("CA4 - RG15 : consulter n'écrit rien et ne déclenche aucun abandon")
    void doit_rester_en_lecture_seule() {
        garnir(X, StatutCourse.EN_COURS);
        inscrire(X, 1, "Bruno");
        scan(inscrire(X, 2, "Chloé"), 1);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.nombreAbandons()).isZero();
        assertThat(depotInscriptions.nombreDeMisesAJour).isZero();
        assertThat(depotPassages.nombreDEnregistrements).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isZero();
    }

    // ---------- fabriques ----------

    private void garnir(UUID id, StatutCourse statut) {
        depotCourses.enregistrer(Course.reconstituer(id, "Backyard", JOUR, statut,
                new ParametresBoucle(400, 1, 5), 10, 5, Set.of(), T0));
        depotCourses.nombreDEnregistrements = 0;
    }

    private Inscription inscrire(UUID courseId, int dossard, String pseudo) {
        return inscrireAvecStatut(courseId, dossard, pseudo, StatutInscription.EN_COURSE);
    }

    private Inscription inscrireAvecStatut(UUID courseId, int dossard, String pseudo, StatutInscription statut) {
        UUID compte = UUID.randomUUID();
        pseudos.put(compte, pseudo);
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compte, dossard,
                JetonQr.generer(), statut);
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }

    private void remplacer(Inscription inscription) {
        depotInscriptions.inscriptions.replaceAll(i -> i.id().equals(inscription.id()) ? inscription : i);
    }

    private void scan(Inscription inscription, int boucle) {
        depotPassages.poser(Passage.scanner(UUID.randomUUID(), inscription, boucle,
                T0.plusSeconds(60L * (boucle - 1) + 10)));
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
