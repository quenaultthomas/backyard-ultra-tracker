package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.backyard.tracker.courses.HorlogeReglableDeTest;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.2 : micro-cache de 1 s des lectures de ConsulterSuivi (CA1 à CA4 ; RG1 à RG3).
 * Signatures supposées : le constructeur de {@code ConsulterSuivi} est inchangé ; {@code ConsulterSuivi.DUREE_DU_CACHE}
 * (Duration, 1 s) ; une lecture = un appel à {@code DepotCourses.parId}, {@code DepotInscriptions.parCourse},
 * {@code AnnuairePseudos.pseudosDe} et {@code DepotPassages.parInscriptions}.
 */
class ConsulterSuiviMicroCacheTest {

    private static final Instant DEPART = Instant.parse("2026-10-21T10:00:00Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 21);
    private static final UUID COURSE = id(0x72);
    private static final UUID COURSE_EN_PREPARATION = id(0x73);
    private static final UUID INCONNUE = id(0x74);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEspionDeTest inscriptions = new DepotInscriptionsEspionDeTest();
    private final AnnuaireSuiviDeTest annuaire = new AnnuaireSuiviDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    private final HorlogeReglableDeTest horloge = new HorlogeReglableDeTest(DEPART);

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - 100 consultations à t puis à t + 999 ms : un seul chargement (Course, Inscriptions, Passages)")
    void doit_charger_une_seule_fois_pendant_moins_d_une_seconde() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);

        for (int i = 0; i < 100; i++) {
            suivi.executer(COURSE);
        }
        horloge.placerA(DEPART.plusMillis(999));
        for (int i = 0; i < 100; i++) {
            suivi.executer(COURSE);
        }

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(1);
        assertThat(inscriptions.appels).containsExactly("parCourse");
        assertThat(passages.nombreDeLecturesGroupees).isEqualTo(1);
    }

    @Test
    @DisplayName("CA1 - à t + 1000 ms : un second chargement")
    void doit_recharger_a_exactement_une_seconde() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);
        suivi.executer(COURSE);
        horloge.placerA(DEPART.plusMillis(999));
        suivi.executer(COURSE);

        horloge.placerA(DEPART.plusMillis(1000));
        suivi.executer(COURSE);

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(2);
        assertThat(inscriptions.appels).containsExactly("parCourse", "parCourse");
        assertThat(passages.nombreDeLecturesGroupees).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - la durée du cache est de 1 s")
    void doit_fixer_la_duree_du_cache_a_une_seconde() {
        assertThat(ConsulterSuivi.DUREE_DU_CACHE).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("CA1 - deux Courses ont chacune leur entrée : charger l'une ne sert pas l'autre")
    void doit_avoir_une_entree_de_cache_par_course() {
        garnirCourseEnCoursDeCinqCoureurs();
        depotCourses.enregistrer(course(id(0x75), StatutCourse.EN_COURS, DEPART));
        ConsulterSuivi suivi = consulteur(passages);

        suivi.executer(COURSE);
        suivi.executer(id(0x75));

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(2);
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - chargement à 10:00:00.000, consultation à 10:00:00.900 : heureServeur, reste et écoulé sont frais")
    void doit_calculer_le_chronometrage_a_l_instant_courant_sans_recharger() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);
        suivi.executer(COURSE);
        horloge.placerA(DEPART.plusMillis(900));

        var instantane = suivi.executer(COURSE);

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(1);
        assertThat(instantane.heureServeur()).isEqualTo(DEPART.plusMillis(900));
        assertThat(instantane.chronometrage().orElseThrow().tempsAvantFinBoucle())
                .isEqualTo(Duration.ofMillis(3_599_100));
        assertThat(instantane.chronometrage().orElseThrow().dureeEcoulee()).isEqualTo(Duration.ofMillis(900));
    }

    @Test
    @DisplayName("CA2 - Boucle courante 1 à 10:59:59.999 puis 2 à 11:00:00.000, sans rechargement")
    void doit_basculer_de_boucle_a_la_milliseconde_malgre_le_cache() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);
        horloge.placerA(Instant.parse("2026-10-21T10:59:59.999Z"));
        var avant = suivi.executer(COURSE);
        horloge.placerA(Instant.parse("2026-10-21T11:00:00Z"));

        var apres = suivi.executer(COURSE);

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(1);
        assertThat(avant.chronometrage().orElseThrow().boucleCourante()).isEqualTo(1);
        assertThat(apres.chronometrage().orElseThrow().boucleCourante()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - boucleEnAttenteDeDecision = 1 à 11:00:00.500 et vide à 11:03:00 (grâce de 3 min), à l'instant courant")
    void doit_recalculer_la_boucle_en_attente_de_decision_a_chaque_requete() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);
        horloge.placerA(Instant.parse("2026-10-21T11:00:00.000Z"));
        suivi.executer(COURSE);
        horloge.placerA(Instant.parse("2026-10-21T11:00:00.500Z"));

        var pendant = suivi.executer(COURSE);
        horloge.placerA(Instant.parse("2026-10-21T11:03:00Z"));
        var apres = suivi.executer(COURSE);

        assertThat(pendant.course().boucleEnAttenteDeDecision(pendant.heureServeur()).getAsInt()).isEqualTo(1);
        assertThat(apres.course().boucleEnAttenteDeDecision(apres.heureServeur())).isEmpty();
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - horloge reculée (chargée à 10:00:05, relue à 10:00:04) : relecture")
    void doit_relire_quand_l_horloge_recule() {
        garnirCourseEnCoursDeCinqCoureurs();
        ConsulterSuivi suivi = consulteur(passages);
        horloge.placerA(Instant.parse("2026-10-21T10:00:05Z"));
        suivi.executer(COURSE);
        horloge.placerA(Instant.parse("2026-10-21T10:00:04Z"));

        suivi.executer(COURSE);

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(2);
    }

    @Test
    @DisplayName("CA3 - une erreur de lecture au rechargement est propagée, non masquée par l'entrée périmée, puis retentée")
    void doit_propager_l_erreur_de_rechargement_puis_reessayer_a_la_consultation_suivante() {
        garnirCourseEnCoursDeCinqCoureurs();
        DepotPassages passagesEnPanne = mock(DepotPassages.class);
        when(passagesEnPanne.parInscriptions(anyCollection()))
                .thenReturn(List.of())
                .thenThrow(new IllegalStateException("base injoignable"))
                .thenReturn(List.of());
        ConsulterSuivi suivi = consulteur(passagesEnPanne);
        suivi.executer(COURSE);
        horloge.placerA(DEPART.plusSeconds(1));

        assertThatThrownBy(() -> suivi.executer(COURSE)).isInstanceOf(IllegalStateException.class);
        var instantane = suivi.executer(COURSE);

        assertThat(instantane.heureServeur()).isEqualTo(DEPART.plusSeconds(1));
        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(3);
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - identifiant inconnu consulté 3 fois : 3 fois CourseIntrouvableException, aucune entrée créée")
    void doit_lever_course_introuvable_a_chaque_fois_sans_creer_d_entree() {
        ConsulterSuivi suivi = consulteur(passages);

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> suivi.executer(INCONNUE)).isInstanceOf(CourseIntrouvableException.class);
        }

        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(3);
    }

    @Test
    @DisplayName("CA4 - Course EN_PREPARATION : ni Inscriptions ni pseudos ni Passages lus")
    void doit_ne_lire_ni_inscriptions_ni_passages_d_une_course_en_preparation() {
        depotCourses.enregistrer(course(COURSE_EN_PREPARATION, StatutCourse.EN_PREPARATION, null));
        ConsulterSuivi suivi = consulteur(passages);

        suivi.executer(COURSE_EN_PREPARATION);
        suivi.executer(COURSE_EN_PREPARATION);

        assertThat(inscriptions.appels).isEmpty();
        assertThat(annuaire.appels).isEmpty();
        assertThat(passages.nombreDeLecturesGroupees).isZero();
    }

    // ---------- décor ----------

    private ConsulterSuivi consulteur(DepotPassages depotPassages) {
        return new ConsulterSuivi(depotCourses, inscriptions.port, annuaire, depotPassages, new DepotLogosDeTest(), horloge);
    }

    private void garnirCourseEnCoursDeCinqCoureurs() {
        depotCourses.enregistrer(course(COURSE, StatutCourse.EN_COURS, DEPART));
        for (int dossard = 1; dossard <= 5; dossard++) {
            UUID compte = id(0xc00 + dossard);
            annuaire.pseudos.put(compte, "coureur" + dossard);
            inscriptions.enMemoire.enregistrer(Inscription.reconstituer(UUID.randomUUID(), COURSE, compte, dossard,
                    JetonQr.generer(), StatutInscription.EN_COURSE));
        }
        inscriptions.appels.clear();
        depotCourses.nombreDeChargementsParId = 0;
    }

    private static Course course(UUID id, StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard du jour", JOUR, statut, new ParametresBoucle(400, 60, 5), 10, 5,
                Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
