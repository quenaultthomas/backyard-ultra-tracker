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
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
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
 * Tests de l'incrément 4.5b : cas d'usage LireDossard (CA1 à CA3 ; RG1 à RG8, RG11, RG12).
 * Signatures attendues : {@code LireDossard(DepotCourses, DepotInscriptions, AnnuairePseudos)},
 * {@code Dossard executer(UUID courseId, UUID inscriptionId)} (record imbriqué {@code LireDossard.Dossard}) avec
 * {@code course()} (Course du domaine), {@code inscription()} (Inscription du domaine, porte dossard, statut et
 * jeton) et {@code pseudo()} (String) ; {@code DepotInscriptions.parIdEtCourse(UUID inscriptionId, UUID courseId)}.
 * Le cas d'usage ne dépend d'aucune horloge ni d'aucun rôle (contrôle côté exposition).
 */
class LireDossardTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID COMPTE_ALICE = UUID.fromString("00000000-0000-0000-0000-00000000aa01");
    private static final UUID COMPTE_BRUNO = UUID.fromString("00000000-0000-0000-0000-00000000aa02");
    private static final UUID COMPTE_CHLOE = UUID.fromString("00000000-0000-0000-0000-00000000aa03");
    private static final JetonQr JETON_ALICE = new JetonQr("JA-jeton-secret-d-alice");
    private static final JetonQr JETON_ALICE_Y = new JetonQr("JY-jeton-secret-d-alice-y");

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final AnnuaireDeTest annuaire = new AnnuaireDeTest();
    private final LireDossard lireDossard = new LireDossard(depotCourses, depotInscriptions, annuaire);

    private final Logger journalDossard = (Logger) LoggerFactory.getLogger(LireDossard.class);
    private final Logger journalListe = (Logger) LoggerFactory.getLogger(ListerInscritsCourse.class);
    private final ListAppender<ILoggingEvent> journal = new ListAppender<>();

    private Inscription alice;
    private Inscription bruno;
    private Inscription chloe;
    private Inscription aliceDansY;

    @BeforeEach
    void preparer() {
        for (Logger logger : List.of(journalDossard, journalListe)) {
            logger.setLevel(Level.TRACE);
            logger.addAppender(journal);
        }
        journal.start();
        depotCourses.enregistrer(course(COURSE_X, "Backyard des Crêtes", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(COURSE_Y, "Backyard express", StatutCourse.EN_PREPARATION));
        annuaire.pseudos.put(COMPTE_ALICE, "Alice");
        annuaire.pseudos.put(COMPTE_BRUNO, "Bruno");
        annuaire.pseudos.put(COMPTE_CHLOE, "Chloé");
        alice = inscrire(COURSE_X, COMPTE_ALICE, 1, JETON_ALICE, StatutInscription.EN_COURSE);
        bruno = inscrire(COURSE_X, COMPTE_BRUNO, 2, JetonQr.generer(), StatutInscription.EN_COURSE);
        chloe = inscrire(COURSE_X, COMPTE_CHLOE, 3, JetonQr.generer(), StatutInscription.EN_COURSE);
        aliceDansY = inscrire(COURSE_Y, COMPTE_ALICE, 1, JETON_ALICE_Y, StatutInscription.EN_COURSE);
    }

    @AfterEach
    void detacher() {
        journalDossard.detachAppender(journal);
        journalListe.detachAppender(journal);
    }

    @Test
    @DisplayName("CA1 - renvoie la Course, le dossard, le statut, le pseudo et le jeton tel que stocké")
    void doit_renvoyer_le_dossard_complet_de_l_inscription_demandee() {
        var dossard = lireDossard.executer(COURSE_X, alice.id());

        assertThat(dossard.course().id()).isEqualTo(COURSE_X);
        assertThat(dossard.course().nom()).isEqualTo("Backyard des Crêtes");
        assertThat(dossard.inscription().id()).isEqualTo(alice.id());
        assertThat(dossard.inscription().dossard()).isEqualTo(1);
        assertThat(dossard.inscription().statut()).isEqualTo(StatutInscription.EN_COURSE);
        assertThat(dossard.inscription().jetonQr()).isEqualTo(JETON_ALICE);
        assertThat(dossard.pseudo()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("CA1 - deux lectures renvoient le même jeton et ne modifient aucune Inscription")
    void doit_renvoyer_le_meme_jeton_a_chaque_lecture_sans_rien_modifier() {
        var premiere = lireDossard.executer(COURSE_X, alice.id());
        var seconde = lireDossard.executer(COURSE_X, alice.id());

        assertThat(premiere.inscription().jetonQr()).isEqualTo(JETON_ALICE).isEqualTo(seconde.inscription().jetonQr());
        assertThat(depotInscriptions.inscriptions).containsExactly(alice, bruno, chloe, aliceDansY);
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::statut)
                .containsOnly(StatutInscription.EN_COURSE);
        assertThat(depotInscriptions.inscriptions).extracting(Inscription::jetonQr)
                .contains(JETON_ALICE, JETON_ALICE_Y);
        assertThat(depotCourses.nombreDEnregistrements).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - une Inscription ABANDON reste lisible, statut conservé")
    void doit_lire_le_dossard_d_une_inscription_en_abandon() {
        Inscription abandon = inscrire(COURSE_X, UUID.randomUUID(), 4, JetonQr.generer(), StatutInscription.ABANDON);
        annuaire.pseudos.put(abandon.compteId(), "Denis");

        var dossard = lireDossard.executer(COURSE_X, abandon.id());

        assertThat(dossard.inscription().statut()).isEqualTo(StatutInscription.ABANDON);
        assertThat(dossard.inscription().jetonQr()).isEqualTo(abandon.jetonQr());
    }

    @Test
    @DisplayName("CA1 - une Inscription VAINQUEUR reste lisible, statut conservé")
    void doit_lire_le_dossard_d_une_inscription_vainqueur() {
        Inscription vainqueur = inscrire(COURSE_X, UUID.randomUUID(), 4, JetonQr.generer(),
                StatutInscription.VAINQUEUR);
        annuaire.pseudos.put(vainqueur.compteId(), "Eva");

        var dossard = lireDossard.executer(COURSE_X, vainqueur.id());

        assertThat(dossard.inscription().statut()).isEqualTo(StatutInscription.VAINQUEUR);
    }

    @Test
    @DisplayName("CA1 - une Course EN_COURS ou TERMINEE ne refuse pas la lecture, statut de Course conservé")
    void doit_lire_le_dossard_quelle_que_soit_le_statut_de_la_course() {
        depotCourses.enregistrer(course(COURSE_X, "Backyard des Crêtes", StatutCourse.EN_COURS));
        assertThat(lireDossard.executer(COURSE_X, alice.id()).course().statut()).isEqualTo(StatutCourse.EN_COURS);

        depotCourses.enregistrer(course(COURSE_X, "Backyard des Crêtes", StatutCourse.TERMINEE));
        var dossard = lireDossard.executer(COURSE_X, alice.id());

        assertThat(dossard.course().statut()).isEqualTo(StatutCourse.TERMINEE);
        assertThat(dossard.inscription().jetonQr()).isEqualTo(JETON_ALICE);
    }

    @Test
    @DisplayName("CA1 - l'annuaire est appelé une seule fois avec le seul identifiant de Compte de l'Inscription")
    void doit_interroger_l_annuaire_une_seule_fois_avec_un_seul_compte() {
        lireDossard.executer(COURSE_X, alice.id());

        assertThat(annuaire.appels).hasSize(1);
        assertThat(annuaire.appels.get(0)).containsExactly(COMPTE_ALICE);
    }

    @Test
    @DisplayName("CA2 - une Course inconnue lève CourseIntrouvableException sans révéler d'identifiant")
    void doit_refuser_une_course_inconnue() {
        UUID inconnue = UUID.fromString("00000000-0000-0000-0000-00000000beef");

        assertThatThrownBy(() -> lireDossard.executer(inconnue, alice.id()))
                .isInstanceOf(CourseIntrouvableException.class)
                .satisfies(e -> assertThat(e.toString() + e.getMessage())
                        .doesNotContain(inconnue.toString(), alice.id().toString()));
        assertThat(annuaire.appels).isEmpty();
    }

    @Test
    @DisplayName("CA2 - l'Inscription d'une autre Course est introuvable et n'est pas révélée")
    void doit_refuser_l_inscription_d_une_autre_course_comme_introuvable() {
        assertThatThrownBy(() -> lireDossard.executer(COURSE_X, aliceDansY.id()))
                .isInstanceOf(InscriptionIntrouvableException.class)
                .satisfies(e -> assertThat(e.toString() + e.getMessage())
                        .doesNotContain(aliceDansY.id().toString(), COURSE_X.toString(), COURSE_Y.toString(),
                                JETON_ALICE_Y.valeur()));
        assertThat(annuaire.appels).isEmpty();
    }

    @Test
    @DisplayName("CA2 - une Inscription inconnue lève InscriptionIntrouvableException sans identifiant")
    void doit_refuser_une_inscription_inconnue() {
        UUID inconnue = UUID.fromString("00000000-0000-0000-0000-00000000dead");

        assertThatThrownBy(() -> lireDossard.executer(COURSE_X, inconnue))
                .isInstanceOf(InscriptionIntrouvableException.class)
                .satisfies(e -> assertThat(e.toString() + e.getMessage())
                        .doesNotContain(inconnue.toString(), COURSE_X.toString()));
    }

    @Test
    @DisplayName("CA2 - Course inconnue et Inscription inconnue : la Course est contrôlée en premier")
    void doit_controler_la_course_avant_l_inscription() {
        assertThatThrownBy(() -> lireDossard.executer(UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(CourseIntrouvableException.class);
    }

    @Test
    @DisplayName("CA3 - un Compte inconnu de l'annuaire donne « Compte inconnu » et un seul WARN anonyme")
    void doit_afficher_compte_inconnu_avec_un_warn_sans_pseudo_ni_compte() {
        annuaire.pseudos.remove(COMPTE_CHLOE);

        var dossard = lireDossard.executer(COURSE_X, chloe.id());

        assertThat(dossard.pseudo()).isEqualTo("Compte inconnu");
        List<ILoggingEvent> warns = journal.list.stream().filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .doesNotContain("Alice", "Bruno", "Chloé", "Compte inconnu")
                .doesNotContain(COMPTE_ALICE.toString(), COMPTE_BRUNO.toString(), COMPTE_CHLOE.toString())
                .doesNotContain(chloe.jetonQr().valeur());
    }

    @Test
    @DisplayName("CA3 - un Compte anonymisé s'affiche tel que l'annuaire le renvoie, sans règle propre ni WARN")
    void doit_afficher_le_pseudo_coureur_anonyme_renvoye_par_l_annuaire() {
        annuaire.pseudos.put(COMPTE_BRUNO, "Coureur anonyme");

        var dossard = lireDossard.executer(COURSE_X, bruno.id());

        assertThat(dossard.pseudo()).isEqualTo("Coureur anonyme");
        assertThat(journal.list).noneMatch(e -> e.getLevel() == Level.WARN);
    }

    @Test
    @DisplayName("CA3 - toString du résultat vaut Dossard[inscription=<id>] : ni jeton, ni pseudo, ni nom de Course")
    void doit_masquer_jeton_pseudo_et_nom_de_course_dans_to_string() {
        var dossard = lireDossard.executer(COURSE_X, alice.id());

        assertThat(dossard.toString()).isEqualTo("Dossard[inscription=" + alice.id() + "]")
                .doesNotContain(JETON_ALICE.valeur(), "Alice", "Backyard des Crêtes");
    }

    @Test
    @DisplayName("CA3 - une lecture réussie journalise une seule ligne INFO sans jeton, pseudo ni identifiant de Compte")
    void doit_journaliser_une_seule_ligne_info_sans_donnee_sensible() {
        lireDossard.executer(COURSE_X, alice.id());

        List<ILoggingEvent> infos = journal.list.stream().filter(e -> e.getLevel() == Level.INFO).toList();
        assertThat(infos).hasSize(1);
        assertThat(infos.get(0).getFormattedMessage())
                .isEqualTo("Dossard consulté (course " + COURSE_X + ", dossard 1)")
                .doesNotContain(JETON_ALICE.valeur(), "Alice", "Backyard des Crêtes", COMPTE_ALICE.toString());
    }

    @Test
    @DisplayName("CA3 - un refus (Course ou Inscription introuvable) ne journalise aucune ligne INFO ou supérieure")
    void doit_ne_rien_journaliser_en_info_lors_d_un_refus() {
        assertThatThrownBy(() -> lireDossard.executer(UUID.randomUUID(), alice.id()));
        assertThatThrownBy(() -> lireDossard.executer(COURSE_X, aliceDansY.id()));

        assertThat(journal.list).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
    }

    private Inscription inscrire(UUID courseId, UUID compteId, int dossard, JetonQr jeton, StatutInscription statut) {
        Inscription inscription = Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard, jeton,
                statut);
        depotInscriptions.enregistrer(inscription);
        return inscription;
    }

    private static Course course(UUID id, String nom, StatutCourse statut) {
        return Course.reconstituer(id, nom, LocalDate.of(2026, 11, 14), statut, new ParametresBoucle(6706, 60, 120),
                10, 24);
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
