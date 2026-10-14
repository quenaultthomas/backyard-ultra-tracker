package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.OriginePassage;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.7 : bouclage dans ConsulterPilotage (CA2 ; RG1, RG4, RG5, RG7, RG8, RG10, RG14).
 *
 * Signatures supposées (spec §4 « Port interne » : noms libres) :
 * - {@code ConsulterPilotage(DepotCourses, DepotInscriptions, DepotPassages, AnnuairePseudos, Clock)} ;
 * - port {@code DepotPassages}: {@code List<Passage> parInscriptions(Collection<UUID> inscriptionIds)} (lecture
 *   groupée, appelée au plus une fois par consultation) ; le double DepotPassagesEnMemoireDeTest la fournit ;
 * - {@code Pilotage} expose en plus {@code Optional<Integer> nombreAyantBoucle()} et
 *   {@code Optional<Integer> nombreEnBoucle()} (vides sans chronométrage) ;
 * - chaque élément de {@code inscritsEnCourse()} expose en plus {@code int nombreBouclesTerminees()} et
 *   {@code Optional<Boolean> boucleCouranteTerminee()} (vide sans chronométrage).
 */
class ConsulterPilotageBouclageTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);

    private static final UUID X = id(1);
    private static final UUID LONGUE = id(2);
    private static final UUID Z = id(3);
    private static final UUID U = id(4);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest depotPassages = new DepotPassagesEnMemoireDeTest();
    private final Map<UUID, String> pseudos = new HashMap<>();
    private final AnnuairePseudos annuaire = ids -> {
        Map<UUID, String> trouves = new HashMap<>();
        ids.stream().filter(pseudos::containsKey).forEach(i -> trouves.put(i, pseudos.get(i)));
        return trouves;
    };

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    @BeforeEach
    void garnir() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, 1, T0));
        depotCourses.enregistrer(course(LONGUE, StatutCourse.EN_COURS, 60, T0));
        depotCourses.enregistrer(course(Z, StatutCourse.TERMINEE, 1, T0));
        depotCourses.enregistrer(course(U, StatutCourse.EN_COURS, 1, null));
        depotCourses.nombreDEnregistrements = 0;
        bruno = inscrire(X, 1, 1, StatutInscription.EN_COURSE, "Bruno");
        chloe = inscrire(X, 2, 2, StatutInscription.EN_COURSE, "Chloé");
        tom = inscrire(X, 3, 3, StatutInscription.EN_COURSE, "Tom");
        poserScans(bruno, 1, 2);
        poserScans(chloe, 1);
    }

    @Test
    @DisplayName("CA2 - X à t0 + 90 s : Bruno (2, vrai), Chloé (1, faux), Tom (0, faux), compteurs 1 / 2")
    void doit_distinguer_ceux_qui_ont_boucle_de_ceux_encore_en_boucle() {
        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.inscritsEnCourse())
                .extracting(i -> i.dossard(), i -> i.nombreBouclesTerminees(), i -> i.boucleCouranteTerminee())
                .containsExactly(tuple(1, 2, java.util.Optional.of(true)), tuple(2, 1, java.util.Optional.of(false)),
                        tuple(3, 0, java.util.Optional.of(false)));
        assertThat(pilotage.nombreAyantBoucle()).contains(1);
        assertThat(pilotage.nombreEnBoucle()).contains(2);
    }

    @Test
    @DisplayName("CA2 - la somme des compteurs vaut le nombre d'inscrits EN_COURSE ; ABANDON et VAINQUEUR exclus (RG1)")
    void doit_exclure_abandon_et_vainqueur_du_tableau_et_des_compteurs_meme_avec_des_passages() {
        Inscription david = inscrire(X, 4, 4, StatutInscription.ABANDON, "David");
        Inscription emma = inscrire(X, 5, 5, StatutInscription.VAINQUEUR, "Emma");
        poserScans(david, 1, 2);
        poserScans(emma, 1, 2);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo())
                .containsExactly("Bruno", "Chloé", "Tom");
        assertThat(pilotage.nombreAyantBoucle().orElseThrow() + pilotage.nombreEnBoucle().orElseThrow())
                .isEqualTo(pilotage.inscritsEnCourse().size());
        assertThat(pilotage.nombreAyantBoucle()).contains(1);
        assertThat(pilotage.nombreEnBoucle()).contains(2);
        assertThat(pilotage.nombreInscrits()).isEqualTo(5);
    }

    @Test
    @DisplayName("CA2 - à t0 + 59 999 ms : Boucle 1, Bruno et Chloé ont bouclé, Tom non (2 / 1)")
    void doit_garder_la_boucle_1_a_une_milliseconde_de_la_bascule() {
        var pilotage = consulterA(T0.plusMillis(59_999)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.boucleCouranteTerminee().orElseThrow())
                .containsExactly(true, true, false);
        assertThat(pilotage.nombreAyantBoucle()).contains(2);
        assertThat(pilotage.nombreEnBoucle()).contains(1);
    }

    @Test
    @DisplayName("CA2 - à t0 + 60 000 ms : Boucle 2, seul Bruno a bouclé (1 / 2), Boucles terminées inchangées")
    void doit_basculer_sur_la_boucle_2_exactement_a_t0_plus_une_duree_de_boucle() {
        var pilotage = consulterA(T0.plusMillis(60_000)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.boucleCouranteTerminee().orElseThrow())
                .containsExactly(true, false, false);
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.nombreBouclesTerminees()).containsExactly(2, 1, 0);
        assertThat(pilotage.nombreAyantBoucle()).contains(1);
        assertThat(pilotage.nombreEnBoucle()).contains(2);
    }

    @Test
    @DisplayName("CA2 - à t0 + 120 s : Boucle 3, personne n'a bouclé (0 / 3), Boucles terminées inchangées")
    void doit_remettre_tout_le_monde_en_boucle_a_la_boucle_suivante() {
        var pilotage = consulterA(T0.plusSeconds(120)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.boucleCouranteTerminee().orElseThrow())
                .containsExactly(false, false, false);
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.nombreBouclesTerminees()).containsExactly(2, 1, 0);
        assertThat(pilotage.nombreAyantBoucle()).contains(0);
        assertThat(pilotage.nombreEnBoucle()).contains(3);
    }

    @Test
    @DisplayName("CA2 - Boucle plafonnée à M = 5 après la fin du temps : un Passage 5 reste « a bouclé » (RG5)")
    void doit_garder_a_boucle_sur_la_derniere_boucle_apres_la_fin_du_temps() {
        poserScans(bruno, 3, 4, 5);

        var pilotage = consulterA(T0.plusSeconds(3600)).executer(X);

        assertThat(pilotage.inscritsEnCourse())
                .extracting(i -> i.dossard(), i -> i.nombreBouclesTerminees(), i -> i.boucleCouranteTerminee())
                .contains(tuple(1, 5, java.util.Optional.of(true)), tuple(2, 1, java.util.Optional.of(false)));
        assertThat(pilotage.nombreAyantBoucle()).contains(1);
    }

    @Test
    @DisplayName("CA2 - un Passage CORRECTION compte comme un Passage SCAN (RG6)")
    void doit_compter_un_passage_correction_pour_le_bouclage() {
        depotPassages.poser(Passage.reconstituer(UUID.randomUUID(), tom.id(), 1, null, OriginePassage.CORRECTION));

        var pilotage = consulterA(T0.plusSeconds(30)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.dossard(), i -> i.boucleCouranteTerminee())
                .contains(tuple(3, java.util.Optional.of(true)));
        assertThat(pilotage.nombreAyantBoucle()).contains(3);
    }

    @Test
    @DisplayName("CA2 - Z TERMINEE : compteurs et indicateurs vides, Boucles terminées renseignées (RG7)")
    void doit_ne_pas_calculer_de_bouclage_pour_une_course_terminee() {
        Inscription anna = inscrire(Z, 1, 11, StatutInscription.EN_COURSE, "Anna");
        poserScans(anna, 1, 2, 3);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(Z);

        assertThat(pilotage.nombreAyantBoucle()).isEmpty();
        assertThat(pilotage.nombreEnBoucle()).isEmpty();
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.boucleCouranteTerminee()).containsOnly(
                java.util.Optional.empty());
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.nombreBouclesTerminees()).containsExactly(3);
    }

    @Test
    @DisplayName("CA2 - U EN_COURS sans heure de départ : compteurs et indicateurs vides, Boucles terminées renseignées")
    void doit_ne_pas_calculer_de_bouclage_sans_heure_de_depart() {
        Inscription anna = inscrire(U, 1, 12, StatutInscription.EN_COURSE, "Anna");
        poserScans(anna, 1);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(U);

        assertThat(pilotage.nombreAyantBoucle()).isEmpty();
        assertThat(pilotage.nombreEnBoucle()).isEmpty();
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.boucleCouranteTerminee()).containsOnly(
                java.util.Optional.empty());
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.nombreBouclesTerminees()).containsExactly(1);
    }

    @Test
    @DisplayName("CA2 - deux Courses de durées différentes au même Clock : bouclage indépendant (RG14)")
    void doit_calculer_le_bouclage_de_chaque_course_independamment() {
        Inscription lea = inscrire(LONGUE, 1, 21, StatutInscription.EN_COURSE, "Léa");
        Inscription max = inscrire(LONGUE, 2, 22, StatutInscription.EN_COURSE, "Max");
        poserScans(lea, 1);
        Instant maintenant = T0.plusSeconds(90);

        var courte = consulterA(maintenant).executer(X);
        var longue = consulterA(maintenant).executer(LONGUE);

        assertThat(courte.chronometrage().orElseThrow().boucleCourante()).isEqualTo(2);
        assertThat(longue.chronometrage().orElseThrow().boucleCourante()).isEqualTo(1);
        assertThat(courte.nombreAyantBoucle()).contains(1);
        assertThat(courte.nombreEnBoucle()).contains(2);
        assertThat(longue.nombreAyantBoucle()).contains(1);
        assertThat(longue.nombreEnBoucle()).contains(1);
        assertThat(longue.inscritsEnCourse()).extracting(i -> i.pseudo(), i -> i.nombreBouclesTerminees())
                .containsExactly(tuple("Léa", 1), tuple("Max", 0));
        assertThat(max).isNotNull();
    }

    @Test
    @DisplayName("CA2 - le Clock n'est lu qu'une fois et les Passages sont lus en une seule requête groupée (RG10)")
    void doit_lire_le_clock_une_seule_fois_et_les_passages_en_une_lecture() {
        AtomicInteger lectures = new AtomicInteger();
        Clock clock = new Clock() {
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
                return T0.plusSeconds(90).plusSeconds(lectures.getAndIncrement() * 60L);
            }
        };

        var pilotage = consulter(clock).executer(X);

        assertThat(lectures.get()).isEqualTo(1);
        assertThat(pilotage.nombreAyantBoucle()).contains(1);
        assertThat(depotPassages.nombreDeLecturesGroupees).isLessThanOrEqualTo(1);
    }

    @Test
    @DisplayName("CA2 - aucune écriture, aucun changement de statut : Tom reste EN_COURSE même très en retard (RG8)")
    void doit_ne_rien_ecrire_ni_changer_de_statut() {
        int passagesAvant = depotPassages.passages.size();

        var pilotage = consulterA(T0.plus(Duration.ofMinutes(3))).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).contains("Tom");
        assertThat(tom.statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::statut)
                .containsOnly(StatutInscription.EN_COURSE);
        assertThat(depotPassages.nombreDEnregistrements).isZero();
        assertThat(depotPassages.passages).hasSize(passagesAvant);
        assertThat(depotCourses.nombreDEnregistrements).isZero();
        assertThat(depotCourses.parId(X).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    // ---------- fabriques ----------

    private ConsulterPilotage consulterA(Instant maintenant) {
        return consulter(Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private ConsulterPilotage consulter(Clock clock) {
        return new ConsulterPilotage(depotCourses, depotInscriptions, depotPassages, annuaire, clock);
    }

    private Inscription inscrire(UUID courseId, int dossard, int compte, StatutInscription statut, String pseudo) {
        UUID compteId = id(0xc00 + compte);
        pseudos.put(compteId, pseudo);
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut);
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }

    private void poserScans(Inscription inscription, int... numeros) {
        for (int numero : numeros) {
            depotPassages.poser(Passage.reconstituer(UUID.randomUUID(), inscription.id(), numero,
                    T0.plusSeconds(numero * 10L), OriginePassage.SCAN));
        }
    }

    private static Course course(UUID id, StatutCourse statut, int dureeBoucleMinutes, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard " + id, JOUR, statut, new ParametresBoucle(400, dureeBoucleMinutes, 5),
                10, 5, Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
