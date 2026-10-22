package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.11 : sortie des contrôles automatiques d'un coureur abandonné manuellement (CA4 ; RG6).
 * Utilise {@code AbandonnerCoureur} (signatures : voir {@link AbandonnerCoureurTest}) puis le contrôle existant
 * {@code AbandonnerCoureursHorsDelai} (inchangé). Course X : Boucles de 1 min, M = 5 ; Course W : M = 2 ;
 * t0 = 14:00:00Z.
 * Adaptés à 7.1 (RG2, RG3) sans affaiblissement : chaque instant de contrôle est décalé de {@code GRACE}, délai de
 * grâce d'une Boucle de 1 min (15 s) ; les attentes sont inchangées.
 */
class AbandonnerCoureursHorsDelaiApresAbandonManuelTest {

    private static final Instant T0 = Instant.parse("2026-10-12T14:00:00Z");
    /** 7.1 RG1 : délai de grâce d'une Boucle de 1 min ; une Boucle n'est tranchée qu'à départ(N+1) + GRACE. */
    private static final Duration GRACE = Duration.ofSeconds(15);
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 12);
    private static final UUID COURSE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final DepotCoursesDeTest courses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest inscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    private final Clock horloge = Clock.fixed(T0, ZoneOffset.UTC);
    private final AbandonnerCoureur abandonManuel = new AbandonnerCoureur(courses, inscriptions, passages, horloge);

    private Inscription bruno;
    private Inscription chloe;
    private Inscription tom;

    private void courseAvec(int nombreMaxBoucles, Integer[] pourBruno, Integer[] pourChloe, Integer[] pourTom) {
        courses.enregistrer(Course.reconstituer(COURSE, "Backyard", JOUR, StatutCourse.EN_COURS,
                new ParametresBoucle(400, 1, 5), 10, nombreMaxBoucles, Set.of(), T0));
        bruno = inscrire(1, pourBruno);
        chloe = inscrire(2, pourChloe);
        tom = inscrire(3, pourTom);
    }

    private static Integer[] boucles(Integer... boucles) {
        return boucles;
    }

    private AbandonnerCoureursHorsDelai controleA(Instant maintenant) {
        return new AbandonnerCoureursHorsDelai(courses, inscriptions, passages,
                Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("CA4 - RG6 : Bruno {1}, Chloé {1} abandonnée MANUEL (Boucle 2), Tom {} ; contrôle à 14:01:00.000Z : Bruno VAINQUEUR, Tom HORS_DELAI 1, Chloé intacte, Course TERMINEE")
    void doit_exclure_le_coureur_abandonne_manuellement_du_controle_de_fin_de_boucle() {
        courseAvec(5, boucles(1), boucles(1), boucles());
        abandonManuel.executer(COURSE, chloe.id(), MotifAbandon.MANUEL);

        var resultat = controleA(T0.plusSeconds(60).plus(GRACE)).executer(COURSE);

        assertThat(resultat.vainqueurs()).isEqualTo(1);
        assertThat(resultat.abandons()).isEqualTo(1);
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(chloe).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(relue(chloe).boucleAbandon()).contains(2);
        assertThat(courses.parId(COURSE).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA4 - RG6 : M = 2, Bruno {1, 2}, Chloé {1, 2}, Tom {1} abandonné (Boucle 2) ; à 14:02:00.000Z : Bruno et Chloé VAINQUEUR, Tom intact")
    void doit_declarer_les_vainqueurs_partages_sans_examiner_le_coureur_abandonne_manuellement() {
        courseAvec(2, boucles(1, 2), boucles(1, 2), boucles(1));
        abandonManuel.executer(COURSE, tom.id(), MotifAbandon.MANUEL);
        int misesAJourAvant = inscriptions.nombreDeMisesAJour;

        var resultat = controleA(T0.plusSeconds(120).plus(GRACE)).executer(COURSE);

        assertThat(resultat.vainqueurs()).isEqualTo(2);
        assertThat(resultat.abandons()).isZero();
        assertThat(relue(bruno).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(chloe).statut()).isEqualTo(StatutInscription.VAINQUEUR);
        assertThat(relue(tom).statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(relue(tom).boucleAbandon()).contains(2);
        assertThat(inscriptions.nombreDeMisesAJour - misesAJourAvant).isEqualTo(2);
        assertThat(courses.parId(COURSE).orElseThrow().statut()).isEqualTo(StatutCourse.TERMINEE);
    }

    @Test
    @DisplayName("CA4 - RG6 : Tom {} abandonné MANUEL (Boucle 1) puis contrôle à 14:01:00.200Z : Tom non modifié, pas de second abandon")
    void doit_ne_pas_abandonner_une_seconde_fois_le_coureur_deja_abandonne_manuellement() {
        courseAvec(5, boucles(1), boucles(1), boucles());
        abandonManuel.executer(COURSE, tom.id(), MotifAbandon.MANUEL);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        int misesAJourAvant = inscriptions.nombreDeMisesAJour;

        var resultat = controleA(T0.plusMillis(60_200).plus(GRACE)).executer(COURSE);

        assertThat(resultat.abandons()).isZero();
        assertThat(inscriptions.nombreDeMisesAJour).isEqualTo(misesAJourAvant);
        assertThat(relue(tom).motifAbandon()).contains(MotifAbandon.MANUEL);
        assertThat(relue(tom).boucleAbandon()).contains(1);
        assertThat(courses.parId(COURSE).orElseThrow().statut()).isEqualTo(StatutCourse.EN_COURS);
    }

    private Inscription inscrire(int dossard, Integer[] boucles) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), COURSE, UUID.randomUUID(), dossard,
                JetonQr.generer(), StatutInscription.EN_COURSE);
        inscriptions.enregistrer(inscription);
        for (Integer boucle : boucles) {
            passages.poser(Passage.scanner(UUID.randomUUID(), inscription, boucle,
                    T0.plus(Duration.ofMinutes(boucle - 1L)).plusSeconds(10)));
        }
        return inscription;
    }

    private Inscription relue(Inscription inscription) {
        List<Inscription> trouvees = inscriptions.inscriptions.stream()
                .filter(i -> i.id().equals(inscription.id())).toList();
        assertThat(trouvees).hasSize(1);
        return trouvees.get(0);
    }
}
