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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.9 : vainqueurs dans ConsulterPilotage (CA3 ; RG10).
 *
 * Signatures supposées : {@code Pilotage} expose en plus {@code int nombreVainqueurs()} et
 * {@code List<InscritVainqueur> inscritsVainqueurs()} (triés par dossard croissant) ; chaque {@code InscritVainqueur}
 * expose {@code dossard()}, {@code pseudo()}, {@code statut()} et {@code int nombreBouclesTerminees()} (nombre de
 * Passages). Les VAINQUEUR ne figurent ni dans {@code inscritsEnCourse()} ni dans {@code inscritsEnAbandon()} ;
 * {@code nombreInscrits()} reste le total tous statuts.
 */
class ConsulterPilotageVainqueursTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID X = id(1);

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
    @DisplayName("CA3 - RG10 : Course TERMINEE, 1 vainqueur et 2 abandons : nombreVainqueurs 1, inscritsEnCourse vide, nombreAbandons 2, nombreInscrits 3")
    void doit_renseigner_le_vainqueur_d_une_course_terminee() {
        garnir(StatutCourse.TERMINEE);
        Inscription bruno = inscrire(1, "Bruno", StatutInscription.VAINQUEUR);
        Inscription chloe = inscrire(2, "Chloé", StatutInscription.EN_COURSE);
        Inscription tom = inscrire(3, "Tom", StatutInscription.EN_COURSE);
        scan(bruno, 1);
        remplacer(chloe.abandonner(MotifAbandon.HORS_DELAI, 1));
        remplacer(tom.abandonner(MotifAbandon.HORS_DELAI, 1));

        var pilotage = consulterA(T0.plusSeconds(70)).executer(X);

        assertThat(pilotage.nombreVainqueurs()).isEqualTo(1);
        assertThat(pilotage.inscritsVainqueurs())
                .extracting(v -> v.dossard(), v -> v.pseudo(), v -> v.statut(), v -> v.nombreBouclesTerminees())
                .containsExactly(tuple(1, "Bruno", StatutInscription.VAINQUEUR, 1));
        assertThat(pilotage.inscritsEnCourse()).isEmpty();
        assertThat(pilotage.nombreAbandons()).isEqualTo(2);
        assertThat(pilotage.inscritsEnAbandon()).extracting(a -> a.dossard()).containsExactly(2, 3);
        assertThat(pilotage.nombreInscrits()).isEqualTo(3);
        assertThat(pilotage.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - RG10 : plusieurs vainqueurs (posés directement) triés par dossard croissant")
    void doit_trier_les_vainqueurs_par_dossard() {
        garnir(StatutCourse.TERMINEE);
        inscrire(3, "Tom", StatutInscription.VAINQUEUR);
        inscrire(1, "Bruno", StatutInscription.VAINQUEUR);
        inscrire(2, "Chloé", StatutInscription.VAINQUEUR);

        var pilotage = consulterA(T0.plusSeconds(70)).executer(X);

        assertThat(pilotage.nombreVainqueurs()).isEqualTo(3);
        assertThat(pilotage.inscritsVainqueurs()).extracting(v -> v.dossard()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("CA3 - RG10 : Course EN_COURS sans vainqueur : nombreVainqueurs 0 et liste vide")
    void doit_renvoyer_une_liste_vide_sans_vainqueur() {
        garnir(StatutCourse.EN_COURS);
        inscrire(1, "Bruno", StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0.plusSeconds(30)).executer(X);

        assertThat(pilotage.nombreVainqueurs()).isZero();
        assertThat(pilotage.inscritsVainqueurs()).isEmpty();
        assertThat(pilotage.inscritsEnCourse()).hasSize(1);
    }

    @Test
    @DisplayName("CA3 - RG10 : un VAINQUEUR dans une Course EN_COURS (ligne posée par SQL) est renseigné et exclu de inscritsEnCourse")
    void doit_renseigner_un_vainqueur_meme_si_la_course_est_en_cours() {
        garnir(StatutCourse.EN_COURS);
        inscrire(1, "Bruno", StatutInscription.VAINQUEUR);
        inscrire(2, "Chloé", StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0.plusSeconds(30)).executer(X);

        assertThat(pilotage.nombreVainqueurs()).isEqualTo(1);
        assertThat(pilotage.inscritsVainqueurs()).extracting(v -> v.pseudo()).containsExactly("Bruno");
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).containsExactly("Chloé");
        assertThat(pilotage.nombreInscrits()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - RG10 : consulter n'écrit rien (lecture seule)")
    void doit_rester_en_lecture_seule() {
        garnir(StatutCourse.TERMINEE);
        inscrire(1, "Bruno", StatutInscription.VAINQUEUR);

        consulterA(T0.plusSeconds(70)).executer(X);

        assertThat(depotInscriptions.nombreDeMisesAJour).isZero();
        assertThat(depotPassages.nombreDEnregistrements).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isZero();
    }

    // ---------- fabriques ----------

    private void garnir(StatutCourse statut) {
        depotCourses.enregistrer(Course.reconstituer(X, "Backyard", JOUR, statut,
                new ParametresBoucle(400, 1, 5), 10, 5, Set.of(), T0));
        depotCourses.nombreDEnregistrements = 0;
    }

    private Inscription inscrire(int dossard, String pseudo, StatutInscription statut) {
        UUID compte = UUID.randomUUID();
        pseudos.put(compte, pseudo);
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), X, compte, dossard,
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
