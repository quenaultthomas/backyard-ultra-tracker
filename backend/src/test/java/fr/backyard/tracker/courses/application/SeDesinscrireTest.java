package fr.backyard.tracker.courses.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.DesinscriptionImpossibleException;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.InscriptionIntrouvableException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ParametresBoucle;
import fr.backyard.tracker.courses.domaine.StatutCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests de l'incrément 3.4 : cas d'usage SeDesinscrire, refus et suppression (CA1, CA2, CA4 ; RG1 à RG3, RG6, RG9, RG11). */
class SeDesinscrireTest {

    private static final UUID COURSE_X = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID COURSE_Y = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID COURSE_Z = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID COURSE_T = UUID.fromString("00000000-0000-0000-0000-0000000000c4");
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID BRUNO = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID CHLOE = UUID.fromString("00000000-0000-0000-0000-0000000000c9");
    private static final UUID ID_INCONNU = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final DepotCoursesAvecSuppressionDeTest depotCourses = new DepotCoursesAvecSuppressionDeTest();
    private final DepotInscriptionsEnMemoireDeTest depotInscriptions = new DepotInscriptionsEnMemoireDeTest();
    private final SeDesinscrire seDesinscrire = new SeDesinscrire(depotCourses, depotInscriptions);

    private final Inscription aliceX = inscription(COURSE_X, ALICE, 1, "01");
    private final Inscription brunoX = inscription(COURSE_X, BRUNO, 2, "02");
    private final Inscription chloeX = inscription(COURSE_X, CHLOE, 3, "03");
    private final Inscription aliceY = inscription(COURSE_Y, ALICE, 1, "04");

    @Test
    @DisplayName("CA1 - Bruno se désinscrit : son Inscription n'existe plus et X compte 2 inscrits")
    void doit_supprimer_l_inscription_du_coureur_et_liberer_sa_place() {
        garnir();

        seDesinscrire.executer(BRUNO, brunoX.id());

        assertThat(depotInscriptions.inscriptions).doesNotContain(brunoX);
        assertThat(depotInscriptions.parIdEtCompte(brunoX.id(), BRUNO)).isEmpty();
        assertThat(depotInscriptions.nombreInscrits(COURSE_X)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA1 - les Inscriptions d'Alice (X et Y) et de Chloé restent inchangées (dossards, jetons)")
    void doit_laisser_les_autres_inscriptions_inchangees() {
        garnir();

        seDesinscrire.executer(BRUNO, brunoX.id());

        assertThat(depotInscriptions.inscriptions).containsExactlyInAnyOrder(aliceX, chloeX, aliceY);
        assertThat(depotInscriptions.parIdEtCompte(aliceX.id(), ALICE)).get()
                .satisfies(i -> {
                    assertThat(i.dossard()).isEqualTo(1);
                    assertThat(i.jetonQr()).isEqualTo(aliceX.jetonQr());
                });
        assertThat(depotInscriptions.parIdEtCompte(chloeX.id(), CHLOE)).get()
                .satisfies(i -> {
                    assertThat(i.dossard()).isEqualTo(3);
                    assertThat(i.jetonQr()).isEqualTo(chloeX.jetonQr());
                });
        assertThat(depotInscriptions.parIdEtCompte(aliceY.id(), ALICE)).contains(aliceY);
    }

    @Test
    @DisplayName("CA1 - la Course X n'est ni modifiée ni enregistrée ni supprimée")
    void doit_laisser_la_course_inchangee() {
        garnir();
        Course avant = depotCourses.parId(COURSE_X).orElseThrow();

        seDesinscrire.executer(BRUNO, brunoX.id());

        assertThat(depotCourses.parId(COURSE_X)).containsSame(avant);
        assertThat(depotCourses.suppressions).isEmpty();
        assertThat(depotCourses.toutes()).hasSize(4);
    }

    @Test
    @DisplayName("CA1 - la Course est chargée par parIdPourModification (verrou, RG8)")
    void doit_charger_la_course_sous_verrou() {
        garnir();

        seDesinscrire.executer(BRUNO, brunoX.id());

        assertThat(depotCourses.chargementsPourModification).containsExactly(COURSE_X);
    }

    @Test
    @DisplayName("CA1 - un second appel avec la même Inscription : InscriptionIntrouvableException")
    void doit_refuser_une_seconde_desinscription_de_la_meme_inscription() {
        garnir();
        seDesinscrire.executer(BRUNO, brunoX.id());

        Throwable erreur = catchThrowable(() -> seDesinscrire.executer(BRUNO, brunoX.id()));

        assertThat(erreur).isInstanceOf(InscriptionIntrouvableException.class);
        assertThat(depotInscriptions.nombreInscrits(COURSE_X)).isEqualTo(2);
    }

    @Test
    @DisplayName("CA2 - Alice se désinscrit d'une Course EN_COURS puis d'une TERMINEE : refus, Inscriptions conservées")
    void doit_refuser_la_desinscription_d_une_course_demarree_ou_terminee() {
        garnir();
        Inscription aliceZ = inscription(COURSE_Z, ALICE, 1, "05");
        Inscription aliceT = inscription(COURSE_T, ALICE, 1, "06");
        depotInscriptions.enregistrer(aliceZ);
        depotInscriptions.enregistrer(aliceT);

        Throwable pourZ = catchThrowable(() -> seDesinscrire.executer(ALICE, aliceZ.id()));
        Throwable pourT = catchThrowable(() -> seDesinscrire.executer(ALICE, aliceT.id()));

        assertThat(pourZ).isInstanceOf(DesinscriptionImpossibleException.class);
        assertThat(pourT).isInstanceOf(DesinscriptionImpossibleException.class);
        assertThat(depotInscriptions.inscriptions).contains(aliceZ, aliceT);
        assertThat(depotInscriptions.inscriptions).hasSize(6);
    }

    @Test
    @DisplayName("CA2 - Bruno passe l'identifiant de l'Inscription d'Alice : introuvable, rien n'est supprimé")
    void doit_refuser_l_inscription_d_autrui_comme_introuvable() {
        garnir();

        Throwable erreur = catchThrowable(() -> seDesinscrire.executer(BRUNO, aliceX.id()));

        assertThat(erreur).isInstanceOf(InscriptionIntrouvableException.class);
        assertThat(depotInscriptions.inscriptions).containsExactlyInAnyOrder(aliceX, brunoX, chloeX, aliceY);
    }

    @Test
    @DisplayName("CA2 - un identifiant d'Inscription inconnu : InscriptionIntrouvableException")
    void doit_refuser_une_inscription_inconnue() {
        garnir();

        Throwable erreur = catchThrowable(() -> seDesinscrire.executer(ALICE, ID_INCONNU));

        assertThat(erreur).isInstanceOf(InscriptionIntrouvableException.class);
        assertThat(depotInscriptions.inscriptions).hasSize(4);
    }

    @Test
    @DisplayName("CA2 - l'Inscription d'autrui sur une Course EN_COURS : introuvable, pas DesinscriptionImpossible")
    void doit_signaler_l_introuvable_avant_la_course_non_en_preparation() {
        garnir();
        Inscription aliceZ = inscription(COURSE_Z, ALICE, 1, "05");
        depotInscriptions.enregistrer(aliceZ);

        Throwable erreur = catchThrowable(() -> seDesinscrire.executer(BRUNO, aliceZ.id()));

        assertThat(erreur).isInstanceOf(InscriptionIntrouvableException.class);
        assertThat(depotInscriptions.inscriptions).contains(aliceZ);
    }

    @Test
    @DisplayName("CA4 - les messages des deux exceptions ne contiennent ni identifiant ni jeton")
    void doit_ne_reveler_ni_identifiant_ni_jeton_dans_les_exceptions() {
        garnir();
        Inscription aliceZ = inscription(COURSE_Z, ALICE, 1, "05");
        depotInscriptions.enregistrer(aliceZ);

        Throwable introuvable = catchThrowable(() -> seDesinscrire.executer(BRUNO, aliceX.id()));
        Throwable impossible = catchThrowable(() -> seDesinscrire.executer(ALICE, aliceZ.id()));

        List<String> textes = List.of(String.valueOf(introuvable), String.valueOf(impossible),
                String.valueOf(introuvable.getMessage()), String.valueOf(impossible.getMessage()));
        List<String> secrets = List.of(aliceX.id().toString(), aliceZ.id().toString(), aliceX.jetonQr().valeur(),
                aliceZ.jetonQr().valeur(), ALICE.toString(), BRUNO.toString(), COURSE_X.toString(),
                COURSE_Z.toString());
        for (String texte : textes) {
            for (String secret : secrets) {
                assertThat(texte).doesNotContain(secret);
            }
        }
    }

    private void garnir() {
        depotCourses.enregistrer(course(COURSE_X, "Backyard des Crêtes", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(COURSE_Y, "Backyard express", StatutCourse.EN_PREPARATION));
        depotCourses.enregistrer(course(COURSE_Z, "Backyard en cours", StatutCourse.EN_COURS));
        depotCourses.enregistrer(course(COURSE_T, "Backyard terminée", StatutCourse.TERMINEE));
        depotInscriptions.enregistrer(aliceX);
        depotInscriptions.enregistrer(brunoX);
        depotInscriptions.enregistrer(chloeX);
        depotInscriptions.enregistrer(aliceY);
    }

    private static Course course(UUID id, String nom, StatutCourse statut) {
        return Course.reconstituer(id, nom, LocalDate.of(2026, 11, 14), statut, new ParametresBoucle(6706, 60, 120), 3,
                24);
    }

    private static Inscription inscription(UUID courseId, UUID compteId, int dossard, String suffixeJeton) {
        return Inscription.reconstituer(UUID.randomUUID(), courseId, compteId, dossard,
                new JetonQr(String.format("%043d", Integer.parseInt(suffixeJeton))), StatutInscription.EN_COURSE);
    }
}
