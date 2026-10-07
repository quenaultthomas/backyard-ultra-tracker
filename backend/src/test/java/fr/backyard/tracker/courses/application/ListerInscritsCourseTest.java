package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Tests de l'incrément 3.5 : cas d'usage ListerInscritsCourse (CA1 à CA3 ; RG1 à RG7, RG11).
 * Signature attendue : {@code ListerInscritsCourse(DepotCourses, DepotInscriptions, AnnuairePseudos)} et
 * {@code InscritsCourse executer(UUID courseId)} avec {@code course()}, {@code inscrits()} (liste de
 * {@code Inscrit(inscriptionId, dossard, pseudo, statut)}), {@code nombreInscrits()}, {@code placesRestantes()},
 * {@code complete()}.
 */
class ListerInscritsCourseTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID COMPTE_ALICE = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
    private static final UUID COMPTE_BRUNO = UUID.fromString("00000000-0000-0000-0000-00000000aa02");
    private static final UUID COMPTE_CHLOE = UUID.fromString("00000000-0000-0000-0000-00000000aa03");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final AnnuaireDeTest annuaire = new AnnuaireDeTest();
    private final ListerInscritsCourse listerInscrits =
            new ListerInscritsCourse(depotCourses, depotInscriptions, annuaire);

    private final Logger journalDuCasDUsage = (Logger) LoggerFactory.getLogger(ListerInscritsCourse.class);
    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    @BeforeEach
    void capturerLeJournal() {
        journalDuCasDUsage.setLevel(Level.TRACE);
        journal.start();
        journalDuCasDUsage.addAppender(journal);
        depotCourses.enregistrer(course(COURSE_X, 3));
        depotCourses.enregistrer(course(COURSE_Y, 10));
        annuaire.pseudos.put(COMPTE_ALICE, "Alice");
        annuaire.pseudos.put(COMPTE_BRUNO, "Bruno");
        annuaire.pseudos.put(COMPTE_CHLOE, "Chloé");
    }

    @AfterEach
    void detacherLeJournal() {
        journalDuCasDUsage.detachAppender(journal);
    }

    @Test
    @DisplayName("CA1 - trie les inscrits par dossard croissant et calcule les nombres d'une Course complète")
    void doit_trier_les_inscrits_par_dossard_et_signaler_la_course_complete() {
        inscrire(COURSE_X, COMPTE_ALICE, 3);
        inscrire(COURSE_X, COMPTE_BRUNO, 1);
        inscrire(COURSE_X, COMPTE_CHLOE, 2);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.inscrits()).extracting(i -> i.dossard(), i -> i.pseudo())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(1, "Bruno"),
                        org.assertj.core.groups.Tuple.tuple(2, "Chloé"),
                        org.assertj.core.groups.Tuple.tuple(3, "Alice"));
        assertThat(resultat.nombreInscrits()).isEqualTo(3);
        assertThat(resultat.placesRestantes()).isZero();
        assertThat(resultat.complete()).isTrue();
    }

    @Test
    @DisplayName("CA1 - interroge l'annuaire une seule fois avec les trois identifiants de Compte")
    void doit_interroger_l_annuaire_une_seule_fois_pour_tous_les_inscrits() {
        inscrire(COURSE_X, COMPTE_ALICE, 3);
        inscrire(COURSE_X, COMPTE_BRUNO, 1);
        inscrire(COURSE_X, COMPTE_CHLOE, 2);

        listerInscrits.executer(COURSE_X);

        assertThat(annuaire.appels).hasSize(1);
        assertThat(annuaire.appels.get(0)).containsExactlyInAnyOrder(COMPTE_ALICE, COMPTE_BRUNO, COMPTE_CHLOE);
    }

    @Test
    @DisplayName("CA1 - le nombre d'inscrits est égal à la taille de la liste, et reste cohérent avec les places")
    void doit_avoir_un_nombre_d_inscrits_egal_a_la_taille_de_la_liste() {
        inscrire(COURSE_X, COMPTE_ALICE, 1);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.nombreInscrits()).isEqualTo(resultat.inscrits().size()).isEqualTo(1);
        assertThat(resultat.placesRestantes()).isEqualTo(2);
        assertThat(resultat.complete()).isFalse();
    }

    @Test
    @DisplayName("CA2 - une Course sans Inscription donne une liste vide et aucun appel à l'annuaire")
    void doit_renvoyer_une_liste_vide_sans_appeler_l_annuaire_pour_une_course_sans_inscription() {
        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.inscrits()).isEmpty();
        assertThat(resultat.nombreInscrits()).isZero();
        assertThat(resultat.placesRestantes()).isEqualTo(3);
        assertThat(resultat.complete()).isFalse();
        assertThat(annuaire.appels.stream().allMatch(Collection::isEmpty)).isTrue();
    }

    @Test
    @DisplayName("CA2 - une Course inconnue lève CourseIntrouvableException dont le message ne contient aucun identifiant")
    void doit_refuser_une_course_inconnue_sans_reveler_l_identifiant() {
        UUID inconnue = UUID.fromString("00000000-0000-0000-0000-00000000beef");

        assertThatThrownBy(() -> listerInscrits.executer(inconnue))
                .isInstanceOf(CourseIntrouvableException.class)
                .satisfies(e -> {
                    assertThat(e.toString()).doesNotContain(inconnue.toString());
                    assertThat(e.getMessage()).doesNotContain(inconnue.toString());
                });
    }

    @Test
    @DisplayName("CA2 - une Course dépassée (plus d'inscrits que le maximum) a 0 place restante, jamais négatif")
    void doit_plafonner_les_places_restantes_a_zero_quand_il_y_a_plus_d_inscrits_que_le_maximum() {
        depotCourses.enregistrer(course(COURSE_X, 2));
        inscrire(COURSE_X, COMPTE_ALICE, 1);
        inscrire(COURSE_X, COMPTE_BRUNO, 2);
        inscrire(COURSE_X, COMPTE_CHLOE, 3);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.nombreInscrits()).isEqualTo(3);
        assertThat(resultat.placesRestantes()).isZero();
        assertThat(resultat.complete()).isTrue();
    }

    @Test
    @DisplayName("CA3 - ne renvoie que les Inscriptions de la Course demandée, jamais celles d'une autre")
    void doit_ne_renvoyer_que_les_inscriptions_de_la_course_demandee() {
        inscrire(COURSE_X, COMPTE_ALICE, 1);
        inscrire(COURSE_X, COMPTE_BRUNO, 2);
        inscrire(COURSE_X, COMPTE_CHLOE, 3);
        Inscription alicePourY = inscrire(COURSE_Y, COMPTE_ALICE, 1);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.inscrits()).hasSize(3);
        assertThat(resultat.inscrits()).extracting(i -> i.inscriptionId()).doesNotContain(alicePourY.id());
        assertThat(listerInscrits.executer(COURSE_Y).inscrits()).hasSize(1);
    }

    @Test
    @DisplayName("CA3 - un Compte absent de l'annuaire garde sa ligne avec le pseudo « Compte inconnu »")
    void doit_conserver_la_ligne_d_un_compte_inconnu_avec_le_pseudo_compte_inconnu() {
        annuaire.pseudos.remove(COMPTE_CHLOE);
        inscrire(COURSE_X, COMPTE_ALICE, 1);
        inscrire(COURSE_X, COMPTE_BRUNO, 2);
        inscrire(COURSE_X, COMPTE_CHLOE, 3);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.inscrits()).extracting(i -> i.pseudo()).containsExactly("Alice", "Bruno",
                "Compte inconnu");
        assertThat(resultat.nombreInscrits()).isEqualTo(3);
    }

    @Test
    @DisplayName("CA3 - le Compte inconnu provoque un unique WARN, sans pseudo ni identifiant de Compte")
    void doit_emettre_un_unique_warn_sans_pseudo_ni_identifiant_de_compte() {
        annuaire.pseudos.remove(COMPTE_CHLOE);
        inscrire(COURSE_X, COMPTE_ALICE, 1);
        inscrire(COURSE_X, COMPTE_BRUNO, 2);
        inscrire(COURSE_X, COMPTE_CHLOE, 3);

        listerInscrits.executer(COURSE_X);

        assertThat(journal.list).hasSize(1);
        ILoggingEvent warn = journal.list.get(0);
        assertThat(warn.getLevel()).isEqualTo(Level.WARN);
        assertThat(warn.getFormattedMessage())
                .doesNotContain("Alice", "Bruno", "Chloé", "Compte inconnu")
                .doesNotContain(COMPTE_ALICE.toString(), COMPTE_BRUNO.toString(), COMPTE_CHLOE.toString());
    }

    @Test
    @DisplayName("CA3 - aucun journal quand tous les Comptes sont connus (aucune ligne INFO)")
    void doit_ne_rien_journaliser_quand_tous_les_comptes_sont_connus() {
        inscrire(COURSE_X, COMPTE_ALICE, 1);
        inscrire(COURSE_X, COMPTE_BRUNO, 2);

        listerInscrits.executer(COURSE_X);

        assertThat(journal.list).isEmpty();
    }

    @Test
    @DisplayName("CA3 - renvoie les statuts réels des Inscriptions sans les modifier")
    void doit_renvoyer_les_statuts_reels_sans_modifier_les_inscriptions() {
        Inscription enCourse = inscrire(COURSE_X, COMPTE_ALICE, 1, StatutInscription.EN_COURSE);
        Inscription abandon = inscrire(COURSE_X, COMPTE_BRUNO, 2, StatutInscription.ABANDON);
        Inscription vainqueur = inscrire(COURSE_X, COMPTE_CHLOE, 3, StatutInscription.VAINQUEUR);

        var resultat = listerInscrits.executer(COURSE_X);

        assertThat(resultat.inscrits()).extracting(i -> i.statut())
                .containsExactly(StatutInscription.EN_COURSE, StatutInscription.ABANDON,
                        StatutInscription.VAINQUEUR);
        assertThat(depotInscriptions.inscriptions).containsExactly(enCourse, abandon, vainqueur);
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::statut)
                .containsExactly(StatutInscription.EN_COURSE, StatutInscription.ABANDON,
                        StatutInscription.VAINQUEUR);
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(2);
    }

    private Inscription inscrire(UUID courseId, UUID compteId, int dossard) {
        return inscrire(courseId, compteId, dossard, StatutInscription.EN_COURSE);
    }

    private Inscription inscrire(UUID courseId, UUID compteId, int dossard, StatutInscription statut) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                JetonQr.generer(), statut);
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }

    private static Course course(UUID id, int nombreMaxParticipants) {
        return Course.reconstituer(id, "Backyard " + id, LocalDate.of(2026, 11, 14), StatutCourse.EN_PREPARATION,
                new ParametresBoucle(6706, 60, 120), nombreMaxParticipants, 24);
    }

    /** Annuaire factice : enregistre chaque appel, omet les Comptes inconnus. */
    private static final class AnnuaireDeTest implements AnnuairePseudos {
        final Map<UUID, String> pseudos = new HashMap<>();
        final List<Collection<UUID>> appels = new ArrayList<>();

        @Override
        public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
            appels.add(List.copyOf(idsComptes));
            Map<UUID, String> trouves = new HashMap<>();
            idsComptes.stream().filter(pseudos::containsKey).forEach(id -> trouves.put(id, pseudos.get(id)));
            return trouves;
        }
    }
}
