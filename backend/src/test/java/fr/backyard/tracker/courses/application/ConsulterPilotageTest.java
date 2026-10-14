package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.CourseNonDemarreeException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 4.2 : cas d'usage ConsulterPilotage (CA5 ; RG6 à RG8, RG10).
 * Signatures attendues : {@code ConsulterPilotage(DepotCourses, DepotInscriptions, AnnuairePseudos, Clock)} ;
 * {@code Pilotage executer(UUID courseId)} où {@code Pilotage} (record imbriqué) expose {@code course()},
 * {@code heureServeur()} (Instant), {@code chronometrage()} ({@code Optional<Chronometrage>}),
 * {@code nombreInscrits()} et {@code inscritsEnCourse()} (liste de records {@code Inscrit(inscriptionId, dossard,
 * pseudo, statut)}).
 */
class ConsulterPilotageTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final LocalDate JOUR = LocalDate.of(2026, 10, 9);

    private static final UUID X = id(1);
    private static final UUID Y = id(2);
    private static final UUID Z = id(3);
    private static final UUID U = id(4);
    private static final UUID INCONNUE = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private static final UUID COMPTE_BRUNO = id(0xb1);
    private static final UUID COMPTE_CHLOE = id(0xb2);
    private static final UUID COMPTE_DAVID = id(0xb3);
    private static final UUID COMPTE_EMMA = id(0xb4);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final AnnuaireDeTest annuaire = new AnnuaireDeTest();

    @BeforeEach
    void garnirLesDepots() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, T0));
        depotCourses.enregistrer(course(Y, StatutCourse.EN_PREPARATION, null));
        depotCourses.enregistrer(course(Z, StatutCourse.TERMINEE, T0));
        depotCourses.enregistrer(course(U, StatutCourse.EN_COURS, null));
        depotCourses.nombreDEnregistrements = 0;
        annuaire.pseudos.put(COMPTE_BRUNO, "Bruno");
        annuaire.pseudos.put(COMPTE_CHLOE, "Coureur anonyme");
        annuaire.pseudos.put(COMPTE_DAVID, "David");
        annuaire.pseudos.put(COMPTE_EMMA, "Emma");
    }

    @Test
    @DisplayName("CA5 - X à t0 + 60 000 ms : Boucle 2, instant du Clock restitué dans heureServeur")
    void doit_calculer_la_boucle_courante_de_x_a_partir_de_l_instant_du_clock() {
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        Instant maintenant = T0.plusMillis(60_000);

        var pilotage = consulterA(maintenant).executer(X);

        assertThat(pilotage.heureServeur()).isEqualTo(maintenant);
        assertThat(pilotage.chronometrage()).isPresent();
        assertThat(pilotage.chronometrage().orElseThrow().boucleCourante()).isEqualTo(2);
        assertThat(pilotage.chronometrage().orElseThrow().tempsAvantFinBoucle()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(pilotage.chronometrage().orElseThrow().dureeEcoulee()).isEqualTo(Duration.ofMillis(60_000));
    }

    @Test
    @DisplayName("CA5 - X : inscritsEnCourse = Bruno 1 puis Chloé 2, triés par dossard, ABANDON et VAINQUEUR exclus")
    void doit_ne_lister_que_les_inscrits_en_course_tries_par_dossard() {
        inscrire(X, COMPTE_CHLOE, 2, StatutInscription.EN_COURSE);
        inscrire(X, COMPTE_DAVID, 3, StatutInscription.ABANDON);
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        inscrire(X, COMPTE_EMMA, 4, StatutInscription.VAINQUEUR);

        var pilotage = consulterA(T0.plusMillis(60_000)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.dossard(), i -> i.statut())
                .containsExactly(tuple(1, StatutInscription.EN_COURSE), tuple(2, StatutInscription.EN_COURSE));
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).doesNotContain("David", "Emma");
    }

    @Test
    @DisplayName("CA5 - X : nombreInscrits compte toutes les Inscriptions, quel que soit leur statut")
    void doit_compter_toutes_les_inscriptions_de_la_course() {
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        inscrire(X, COMPTE_CHLOE, 2, StatutInscription.EN_COURSE);
        inscrire(X, COMPTE_DAVID, 3, StatutInscription.ABANDON);
        inscrire(X, COMPTE_EMMA, 4, StatutInscription.VAINQUEUR);

        var pilotage = consulterA(T0.plusMillis(60_000)).executer(X);

        assertThat(pilotage.nombreInscrits()).isEqualTo(4);
        assertThat(pilotage.inscritsEnCourse()).hasSize(2);
    }

    @Test
    @DisplayName("CA5 - le pseudo « Coureur anonyme » de l'annuaire est restitué tel quel")
    void doit_restituer_le_pseudo_coureur_anonyme_tel_quel() {
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        inscrire(X, COMPTE_CHLOE, 2, StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0.plusMillis(60_000)).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.dossard(), i -> i.pseudo())
                .containsExactly(tuple(1, "Bruno"), tuple(2, "Coureur anonyme"));
    }

    @Test
    @DisplayName("CA5 - un Compte non résolu par l'annuaire garde sa ligne avec le pseudo « Compte inconnu »")
    void doit_utiliser_compte_inconnu_quand_l_annuaire_ne_resout_pas_le_pseudo() {
        annuaire.pseudos.remove(COMPTE_BRUNO);
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0).executer(X);

        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).containsExactly("Compte inconnu");
    }

    @Test
    @DisplayName("CA5 - isolation : les Inscriptions d'une autre Course n'apparaissent pas ni ne sont comptées")
    void doit_ignorer_les_inscriptions_des_autres_courses() {
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        inscrire(Z, COMPTE_DAVID, 1, StatutInscription.EN_COURSE);
        inscrire(U, COMPTE_EMMA, 1, StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0.plusMillis(60_000)).executer(X);

        assertThat(pilotage.nombreInscrits()).isEqualTo(1);
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).containsExactly("Bruno");
    }

    @Test
    @DisplayName("CA5 - aucune écriture dans les dépôts : Courses et Inscriptions inchangées")
    void doit_ne_rien_ecrire_dans_les_depots() {
        Inscription bruno = inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        Inscription david = inscrire(X, COMPTE_DAVID, 2, StatutInscription.ABANDON);
        int inscriptionsAvant = depotInscriptions.inscriptions.size();
        Course courseAvant = depotCourses.parId(X).orElseThrow();

        // temps largement écoulé : aucune transition ne doit être déclenchée (RG9)
        consulterA(T0.plus(Duration.ofHours(2))).executer(X);

        assertThat(depotCourses.nombreDEnregistrements).isZero();
        assertThat(depotCourses.parId(X).orElseThrow()).isSameAs(courseAvant);
        assertThat(courseAvant.statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(depotInscriptions.inscriptions).hasSize(inscriptionsAvant).containsExactly(bruno, david);
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::statut)
                .containsExactly(StatutInscription.EN_COURSE, StatutInscription.ABANDON);
    }

    @Test
    @DisplayName("CA5 - le Clock n'est lu qu'une fois : heureServeur et chronométrage viennent de la même lecture")
    void doit_lire_le_clock_une_seule_fois() {
        inscrire(X, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);
        ClockAvanceALaLecture clock = new ClockAvanceALaLecture(T0.plusMillis(60_000), Duration.ofSeconds(7));

        var pilotage = consulter(clock).executer(X);

        assertThat(clock.lectures.get()).isEqualTo(1);
        assertThat(pilotage.heureServeur()).isEqualTo(T0.plusMillis(60_000));
        assertThat(pilotage.chronometrage().orElseThrow().dureeEcoulee()).isEqualTo(Duration.ofMillis(60_000));
        assertThat(pilotage.chronometrage().orElseThrow().boucleCourante()).isEqualTo(2);
    }

    @Test
    @DisplayName("CA5 - Y EN_PREPARATION : CourseNonDemarreeException, sans lecture des Inscriptions ni annuaire")
    void doit_refuser_le_pilotage_d_une_course_non_demarree() {
        inscrire(Y, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        assertThatThrownBy(() -> consulterA(T0).executer(Y)).isInstanceOf(CourseNonDemarreeException.class);

        assertThat(annuaire.appels).isZero();
    }

    @Test
    @DisplayName("CA5 - Z TERMINEE : chronométrage vide, inscrits EN_COURSE renvoyés tels quels")
    void doit_renvoyer_un_chronometrage_vide_pour_une_course_terminee() {
        inscrire(Z, COMPTE_BRUNO, 1, StatutInscription.VAINQUEUR);
        inscrire(Z, COMPTE_DAVID, 2, StatutInscription.ABANDON);

        var pilotage = consulterA(T0.plusSeconds(90)).executer(Z);

        assertThat(pilotage.chronometrage()).isEmpty();
        assertThat(pilotage.course().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(pilotage.heureServeur()).isEqualTo(T0.plusSeconds(90));
        assertThat(pilotage.nombreInscrits()).isEqualTo(2);
        assertThat(pilotage.inscritsEnCourse()).isEmpty();
    }

    @Test
    @DisplayName("CA5 - U EN_COURS sans heure de départ : chronométrage vide, liste des inscrits conservée")
    void doit_renvoyer_un_chronometrage_vide_pour_une_course_en_cours_sans_heure_de_depart() {
        inscrire(U, COMPTE_BRUNO, 1, StatutInscription.EN_COURSE);

        var pilotage = consulterA(T0).executer(U);

        assertThat(pilotage.chronometrage()).isEmpty();
        assertThat(pilotage.course().statut()).isEqualTo(StatutCourse.EN_COURS);
        assertThat(pilotage.inscritsEnCourse()).extracting(i -> i.pseudo()).containsExactly("Bruno");
    }

    @Test
    @DisplayName("CA5 - identifiant inconnu : CourseIntrouvableException")
    void doit_refuser_une_course_inconnue() {
        assertThatThrownBy(() -> consulterA(T0).executer(INCONNUE)).isInstanceOf(CourseIntrouvableException.class);
    }

    // ---------- fabriques et doubles ----------

    private ConsulterPilotage consulterA(Instant maintenant) {
        return consulter(Clock.fixed(maintenant, ZoneOffset.UTC));
    }

    private ConsulterPilotage consulter(Clock clock) {
        return new ConsulterPilotage(depotCourses, depotInscriptions, new DepotPassagesEnMemoireDeTest(), annuaire,
                clock);
    }

    private Inscription inscrire(UUID courseId, UUID compteId, int dossard, StatutInscription statut) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut);
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }

    private static Course course(UUID id, StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard des Crêtes " + id, JOUR, statut, new ParametresBoucle(400, 1, 5),
                10, 5, Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }

    /** Clock dont chaque lecture avance l'instant : toute seconde lecture donnerait une valeur différente. */
    private static final class ClockAvanceALaLecture extends Clock {
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

    private static final class AnnuaireDeTest implements AnnuairePseudos {
        final Map<UUID, String> pseudos = new HashMap<>();
        int appels;

        @Override
        public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
            appels++;
            Map<UUID, String> trouves = new HashMap<>();
            idsComptes.stream().filter(pseudos::containsKey).forEach(id -> trouves.put(id, pseudos.get(id)));
            return trouves;
        }
    }
}
