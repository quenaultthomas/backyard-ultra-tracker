package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests de l'incrément 6.2 : ConsulterSuivi étendu (CA1 à CA7 ; RG2 à RG9, RG17).
 * Signatures attendues :
 * <ul>
 * <li>{@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, Clock)}, sans DepotPassages
 * (REMPLACÉ en 6.3 : {@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, DepotPassages, Clock)}) ;</li>
 * <li>{@code Suivi(Course course, Instant heureServeur, Optional<Chronometrage> chronometrage,
 * List<CoureurSuivi> coureursEnCourse, List<AbandonSuivi> abandons, List<CoureurSuivi> vainqueurs)} ;</li>
 * <li>{@code CoureurSuivi(int dossard, String pseudo)} ;</li>
 * <li>{@code AbandonSuivi(int dossard, String pseudo, Optional<Integer> boucleAbandon,
 * Optional<MotifAbandon> motifAbandon)} ;</li>
 * <li>{@code CoureurSuivi} et {@code AbandonSuivi} sont des types imbriqués de {@code ConsulterSuivi} ; leur
 * {@code toString()} et celui de {@code Suivi} n'affichent ni pseudo ni jeton.</li>
 * </ul>
 */
class ConsulterSuiviCoureursTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final Instant MAINTENANT = T0.plusSeconds(1_800);
    private static final UUID X = id(1);
    private static final UUID Y = id(2);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEspionDeTest inscriptions = new DepotInscriptionsEspionDeTest();
    private final AnnuaireSuiviDeTest annuaire = new AnnuaireSuiviDeTest();
    private final DepotPassagesEnMemoireDeTest passages = new DepotPassagesEnMemoireDeTest();
    private final ConsulterSuivi consulterSuivi = new ConsulterSuivi(depotCourses, inscriptions.port, annuaire,
            passages, new DepotLogosDeTest(), Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    @BeforeEach
    void garnir() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, T0));
        depotCourses.enregistrer(course(Y, StatutCourse.EN_COURS, T0));
    }

    // ---------- CA1 ----------

    @Test
    @DisplayName("CA1 - répartit les Inscriptions par statut : en course par dossard, abandon avec Boucle et motif")
    void doit_repartir_chaque_inscription_dans_une_seule_liste_selon_son_statut() {
        inscrire(X, 3, "Karim", StatutInscription.EN_COURSE);
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrireAbandon(X, 2, "Bruno", MotifAbandon.HORS_DELAI, 2);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard(), c -> c.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "Alice"),
                        org.assertj.core.groups.Tuple.tuple(3, "Karim"));
        assertThat(suivi.abandons()).hasSize(1);
        var abandon = suivi.abandons().getFirst();
        assertThat(abandon.dossard()).isEqualTo(2);
        assertThat(abandon.pseudo()).isEqualTo("Bruno");
        assertThat(abandon.boucleAbandon()).contains(2);
        assertThat(abandon.motifAbandon()).contains(MotifAbandon.HORS_DELAI);
        assertThat(suivi.vainqueurs()).isEmpty();
    }

    @Test
    @DisplayName("CA1 - le statut est lu, jamais recalculé : un coureur EN_COURSE dont la Boucle est dépassée reste en course")
    void doit_lire_le_statut_enregistre_sans_le_recalculer() {
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);

        var suivi = new ConsulterSuivi(depotCourses, inscriptions.port, annuaire,
                passages, new DepotLogosDeTest(), Clock.fixed(T0.plusSeconds(6 * 3_600), ZoneOffset.UTC)).executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard()).containsExactly(1);
        assertThat(suivi.abandons()).isEmpty();
        assertThat(inscriptions.enMemoire.nombreDeMisesAJour).isZero();
        assertThat(inscriptions.enMemoire.inscriptions).extracting(Inscription::statut)
                .containsExactly(StatutInscription.EN_COURSE);
    }

    @Test
    @DisplayName("CA1 - isolation : les Inscriptions d'une autre Course n'apparaissent jamais")
    void doit_ignorer_les_inscriptions_d_une_autre_course() {
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrire(Y, 1, "Bruno", StatutInscription.EN_COURSE);
        inscrireAbandon(Y, 2, "Karim", MotifAbandon.MANUEL, 1);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.pseudo()).containsExactly("Alice");
        assertThat(suivi.abandons()).isEmpty();
    }

    // ---------- CA2 ----------

    @Test
    @DisplayName("CA2 - abandons triés par Boucle décroissante, puis dossard croissant, sans Boucle en dernier")
    void doit_trier_les_abandons_par_boucle_decroissante_puis_dossard_avec_les_sans_boucle_en_dernier() {
        inscrireAbandon(X, 4, "Dora", MotifAbandon.MANUEL, 2);
        inscrireAbandon(X, 2, "Bruno", MotifAbandon.AUTRE, 5);
        inscrireAbandon(X, 6, "Fred", MotifAbandon.VOLONTAIRE, 5);
        inscrireAbandonSansBoucleNiMotif(X, 3, "Chloé");

        var abandons = consulterSuivi.executer(X).abandons();

        assertThat(abandons).extracting(a -> a.dossard()).containsExactly(2, 6, 4, 3);
        assertThat(abandons.get(0).motifAbandon()).contains(MotifAbandon.AUTRE);
        assertThat(abandons.get(1).motifAbandon()).contains(MotifAbandon.VOLONTAIRE);
        assertThat(abandons.get(2).boucleAbandon()).contains(2);
        assertThat(abandons.get(3).boucleAbandon()).isEmpty();
        assertThat(abandons.get(3).motifAbandon()).isEmpty();
    }

    @Test
    @DisplayName("CA2 - deux appels successifs donnent le même ordre")
    void doit_donner_le_meme_ordre_a_chaque_appel() {
        inscrireAbandon(X, 4, "Dora", MotifAbandon.MANUEL, 2);
        inscrireAbandon(X, 2, "Bruno", MotifAbandon.AUTRE, 5);
        inscrireAbandon(X, 6, "Fred", MotifAbandon.VOLONTAIRE, 5);
        inscrireAbandonSansBoucleNiMotif(X, 3, "Chloé");

        var premier = consulterSuivi.executer(X).abandons();
        var second = consulterSuivi.executer(X).abandons();

        assertThat(second).isEqualTo(premier);
        assertThat(second).extracting(a -> a.dossard()).containsExactly(2, 6, 4, 3);
    }

    @Test
    @DisplayName("CA2 - plusieurs abandons sans Boucle : triés entre eux par dossard croissant, après les autres")
    void doit_trier_par_dossard_les_abandons_sans_boucle() {
        inscrireAbandonSansBoucleNiMotif(X, 9, "Ines");
        inscrireAbandon(X, 8, "Hugo", MotifAbandon.HORS_DELAI, 1);
        inscrireAbandonSansBoucleNiMotif(X, 5, "Eva");

        assertThat(consulterSuivi.executer(X).abandons()).extracting(a -> a.dossard()).containsExactly(8, 5, 9);
    }

    // ---------- CA3 ----------

    @Test
    @DisplayName("CA3 - Course TERMINEE, vainqueurs partagés : vainqueurs par dossard, abandon séparé, personne en course")
    void doit_lister_les_vainqueurs_partages_par_dossard_et_les_abandons_a_part() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0));
        inscrire(X, 7, "Gaël", StatutInscription.VAINQUEUR);
        inscrire(X, 2, "Bruno", StatutInscription.VAINQUEUR);
        inscrireAbandon(X, 5, "Eva", MotifAbandon.HORS_DELAI, 3);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.vainqueurs()).extracting(v -> v.dossard(), v -> v.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(2, "Bruno"),
                        org.assertj.core.groups.Tuple.tuple(7, "Gaël"));
        assertThat(suivi.abandons()).extracting(a -> a.dossard()).containsExactly(5);
        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - Course TERMINEE, vainqueur unique : un seul vainqueur, aucun coureur en course")
    void doit_lister_un_vainqueur_unique() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0));
        inscrire(X, 9, "Ines", StatutInscription.VAINQUEUR);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.vainqueurs()).extracting(v -> v.dossard()).containsExactly(9);
        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - Course TERMINEE sans vainqueur : vainqueurs vide, abandons listés par dossard")
    void doit_renvoyer_zero_vainqueur_pour_une_course_terminee_sans_vainqueur() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0));
        inscrireAbandon(X, 2, "Bruno", MotifAbandon.VOLONTAIRE, 1);
        inscrireAbandon(X, 1, "Alice", MotifAbandon.VOLONTAIRE, 1);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.vainqueurs()).isEmpty();
        assertThat(suivi.abandons()).extracting(a -> a.dossard()).containsExactly(1, 2);
        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.chronometrage()).isEmpty();
    }

    @Test
    @DisplayName("CA3 - un vainqueur n'est jamais dans les abandons")
    void doit_garder_les_vainqueurs_hors_des_abandons() {
        depotCourses.enregistrer(course(X, StatutCourse.TERMINEE, T0));
        inscrire(X, 1, "Alice", StatutInscription.VAINQUEUR);
        inscrire(X, 2, "Bruno", StatutInscription.VAINQUEUR);

        assertThat(consulterSuivi.executer(X).abandons()).isEmpty();
    }

    // ---------- CA4 ----------

    @Test
    @DisplayName("CA4 - EN_PREPARATION : trois listes vides, ni Inscriptions ni annuaire consultés")
    void doit_renvoyer_trois_listes_vides_sans_rien_lire_pour_une_course_en_preparation() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_PREPARATION, null));
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrire(X, 2, "Bruno", StatutInscription.EN_COURSE);
        inscrireAbandon(X, 3, "Karim", MotifAbandon.MANUEL, 1);
        inscriptions.appels.clear();

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.abandons()).isEmpty();
        assertThat(suivi.vainqueurs()).isEmpty();
        assertThat(inscriptions.appels).isEmpty();
        assertThat(annuaire.appels).isEmpty();
        assertThat(passages.nombreDeLecturesGroupees).isZero(); // 6.3 CA8 : aucun Passage lu non plus
    }

    @Test
    @DisplayName("CA4 - EN_COURS sans heure de départ : listes renseignées, chronométrage vide")
    void doit_renseigner_les_listes_pour_une_course_en_cours_sans_heure_de_depart() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, null));
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrireAbandon(X, 2, "Bruno", MotifAbandon.MANUEL, 1);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.chronometrage()).isEmpty();
        assertThat(suivi.coureursEnCourse()).extracting(c -> c.pseudo()).containsExactly("Alice");
        assertThat(suivi.abandons()).extracting(a -> a.pseudo()).containsExactly("Bruno");
    }

    // ---------- CA5 ----------

    @Test
    @DisplayName("CA5 - pseudos : Alice, Coureur anonyme (annuaire), Compte inconnu (absent de l'annuaire)")
    void doit_afficher_le_pseudo_de_l_annuaire_et_compte_inconnu_quand_il_est_absent() {
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrire(X, 2, "Coureur anonyme", StatutInscription.EN_COURSE);
        inscrireSansComptedansAnnuaire(X, 3);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.pseudo())
                .containsExactly("Alice", "Coureur anonyme", "Compte inconnu");
    }

    @Test
    @DisplayName("CA5 - une seule recherche de pseudos pour tous les Comptes distincts de la Course")
    void doit_interroger_l_annuaire_une_seule_fois_avec_les_comptes_distincts() {
        UUID compteAlice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE).compteId();
        UUID compteBruno = inscrireAbandon(X, 2, "Bruno", MotifAbandon.MANUEL, 1).compteId();
        UUID compteKarim = inscrire(X, 3, "Karim", StatutInscription.EN_COURSE).compteId();

        consulterSuivi.executer(X);

        assertThat(annuaire.appels).hasSize(1);
        assertThat(annuaire.appels.getFirst()).containsExactlyInAnyOrder(compteAlice, compteBruno, compteKarim);
    }

    @Test
    @DisplayName("CA5 - sans Inscription, l'annuaire n'est pas appelé et les listes sont vides")
    void doit_ne_pas_appeler_l_annuaire_sans_inscription() {
        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(suivi.abandons()).isEmpty();
        assertThat(suivi.vainqueurs()).isEmpty();
        assertThat(annuaire.appels).isEmpty();
    }

    @Test
    @DisplayName("CA5 - deux Comptes anonymisés : deux lignes de même libellé, distinguées par le dossard")
    void doit_garder_deux_lignes_coureur_anonyme_de_dossards_distincts() {
        inscrire(X, 4, "Coureur anonyme", StatutInscription.EN_COURSE);
        inscrire(X, 1, "Coureur anonyme", StatutInscription.EN_COURSE);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard(), c -> c.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "Coureur anonyme"),
                        org.assertj.core.groups.Tuple.tuple(4, "Coureur anonyme"));
    }

    // ---------- CA6 ----------

    @Test
    @DisplayName("CA6 (remplacé par 6.3 CA10 pour la partie « sans DepotPassages ») - toString n'expose ni pseudo ni jeton")
    void doit_masquer_pseudos_et_jetons_dans_les_to_string() {
        Inscription alice = inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        Inscription bruno = inscrireAbandon(X, 2, "Brunette", MotifAbandon.MANUEL, 1);

        var suivi = consulterSuivi.executer(X);

        List<String> affichages = new ArrayList<>();
        affichages.add(suivi.toString());
        affichages.add(suivi.coureursEnCourse().getFirst().toString());
        affichages.add(suivi.abandons().getFirst().toString());
        assertThat(affichages).allSatisfy(texte -> assertThat(texte)
                .doesNotContain("Alice", "Brunette", alice.jetonQr().valeur(), bruno.jetonQr().valeur()));
    }

    // ---------- CA7 ----------

    @Test
    @DisplayName("CA7 (modifié par 6.3 RG9) - la Course est lue en premier, puis Inscriptions, pseudos et Passages une fois chacun")
    void doit_lire_la_course_avant_les_inscriptions_puis_les_pseudos() {
        List<String> journal = new ArrayList<>();
        depotCourses.journalDesLectures = journal;
        inscriptions.journalDesLectures = journal;
        annuaire.journalDesLectures = journal;
        passages.journalDesLectures = journal;
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);
        inscrire(X, 2, "Bruno", StatutInscription.EN_COURSE);
        inscrireAbandon(X, 3, "Karim", MotifAbandon.MANUEL, 1);
        journal.clear();

        consulterSuivi.executer(X);

        assertThat(journal).hasSize(4).first().isEqualTo("course");
        assertThat(journal).containsExactlyInAnyOrder("course", "inscriptions", "pseudos", "passages");
    }

    @Test
    @DisplayName("CA7 - aucune écriture ni lecture de verrou, et pas de lecture par ligne (pas de N+1)")
    void doit_se_limiter_a_une_lecture_par_port_quel_que_soit_le_nombre_d_inscrits() {
        for (int dossard = 1; dossard <= 40; dossard++) {
            inscrire(X, dossard, "Coureur" + dossard, StatutInscription.EN_COURSE);
        }
        inscriptions.appels.clear();
        depotCourses.nombreDeChargementsParId = 0;
        depotCourses.nombreDEnregistrements = 0;

        consulterSuivi.executer(X);

        assertThat(inscriptions.appels).containsExactly("parCourse");
        assertThat(annuaire.appels).hasSize(1);
        assertThat(passages.nombreDeLecturesGroupees).isEqualTo(1); // 6.3 RG9 : une lecture groupée, pas par ligne
        assertThat(passages.nombreDeLecturesParBoucle).isZero();
        assertThat(depotCourses.nombreDeChargementsParId).isEqualTo(1);
        assertThat(depotCourses.nombreDeChargementsPourModification).isZero();
        assertThat(depotCourses.nombreDEnregistrements).isZero();
        assertThat(inscriptions.enMemoire.nombreDeMisesAJour).isZero();
    }

    @Test
    @DisplayName("CA7 - heureServeur et chronométrage de 6.1 restent corrects avec des Inscriptions présentes")
    void doit_conserver_heure_serveur_et_chronometrage_avec_des_inscriptions() {
        inscrire(X, 1, "Alice", StatutInscription.EN_COURSE);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.heureServeur()).isEqualTo(MAINTENANT);
        var chrono = suivi.chronometrage().orElseThrow();
        assertThat(chrono.boucleCourante()).isEqualTo(1);
        assertThat(chrono.dureeEcoulee()).isEqualTo(java.time.Duration.ofMinutes(30));
        assertThat(chrono.tempsAvantFinBoucle()).isEqualTo(java.time.Duration.ofMinutes(30));
    }

    // ---------- fabriques ----------

    private Inscription inscrire(UUID courseId, int dossard, String pseudo, StatutInscription statut) {
        return enregistrer(courseId, dossard, pseudo, statut, null, null);
    }

    private Inscription inscrireAbandon(UUID courseId, int dossard, String pseudo, MotifAbandon motif, int boucle) {
        return enregistrer(courseId, dossard, pseudo, StatutInscription.ABANDON, motif, boucle);
    }

    private Inscription inscrireAbandonSansBoucleNiMotif(UUID courseId, int dossard, String pseudo) {
        return enregistrer(courseId, dossard, pseudo, StatutInscription.ABANDON, null, null);
    }

    private Inscription inscrireSansComptedansAnnuaire(UUID courseId, int dossard) {
        return enregistrer(courseId, dossard, null, StatutInscription.EN_COURSE, null, null);
    }

    private Inscription enregistrer(UUID courseId, int dossard, String pseudo, StatutInscription statut,
                                    MotifAbandon motif, Integer boucle) {
        UUID compteId = UUID.randomUUID();
        if (pseudo != null) {
            annuaire.pseudos.put(compteId, pseudo);
        }
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut, motif, boucle);
        inscriptions.enMemoire.enregistrer(inscription);
        return inscription;
    }

    private static Course course(UUID id, StatutCourse statut, Instant demarreeLe) {
        return Course.reconstituer(id, "Backyard " + id, LocalDate.of(2026, 10, 9), statut,
                new ParametresBoucle(400, 60, 5), 100, 5, Set.of(), demarreeLe);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012x", n));
    }
}
