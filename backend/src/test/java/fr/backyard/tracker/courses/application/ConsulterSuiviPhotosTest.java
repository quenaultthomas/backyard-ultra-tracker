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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 10.2 : photoUrl dans le suivi (CA1 à CA4 ; RG1 à RG3).
 * Signatures attendues :
 * <ul>
 * <li>port {@code fr.backyard.tracker.courses.domaine.AnnuairePhotos} avec
 * {@code Map<UUID, String> jetonsPhotoDe(Collection<UUID> idsComptes)} : jeton opaque de photo des Comptes qui en
 * ont une (absent de la Map sinon, jamais d'exception) ;</li>
 * <li>{@code ConsulterSuivi(DepotCourses, DepotInscriptions, AnnuairePseudos, AnnuairePhotos, DepotPassages,
 * DepotLogos, Clock)} ;</li>
 * <li>{@code ConsulterSuivi.CoureurSuivi(int dossard, String pseudo, String photoUrl)}, {@code photoUrl} null sans
 * photo, sinon {@code "/api/photos/" + jeton} (construite uniquement dans le cas d'usage) ;</li>
 * <li>{@code AbandonSuivi} inchangé.</li>
 * </ul>
 */
class ConsulterSuiviPhotosTest {

    private static final Instant T0 = Instant.parse("2026-10-09T14:03:27Z");
    private static final Instant MAINTENANT = T0.plusSeconds(1_800);
    private static final UUID X = id(1);
    private static final String JETON_KARIM = "a".repeat(32);
    private static final String JETON_SOPHIE = "b".repeat(32);
    private static final String JETON_TOM = "c".repeat(32);

    private final DepotCoursesDeTest depotCourses = new DepotCoursesDeTest();
    private final DepotInscriptionsEspionDeTest inscriptions = new DepotInscriptionsEspionDeTest();
    private final AnnuaireSuiviDeTest annuairePseudos = new AnnuaireSuiviDeTest();
    private final AnnuairePhotosDeTest annuairePhotos = new AnnuairePhotosDeTest();
    private final ConsulterSuivi consulterSuivi = new ConsulterSuivi(depotCourses, inscriptions.port,
            annuairePseudos, annuairePhotos, new DepotPassagesEnMemoireDeTest(), new DepotLogosDeTest(),
            Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    @BeforeEach
    void garnir() {
        depotCourses.enregistrer(course(X, StatutCourse.EN_COURS, T0));
    }

    @Test
    @DisplayName("CA1 - en course : photoUrl relative /api/photos/<jeton> avec photo, null sans photo, par dossard")
    void doit_exposer_la_photo_url_des_coureurs_en_course_dans_l_ordre_des_dossards() {
        Inscription karim = inscrire(2, "Karim", StatutInscription.EN_COURSE);
        inscrire(1, "Alice", StatutInscription.EN_COURSE);
        annuairePhotos.jetons.put(karim.compteId(), JETON_KARIM);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard(), c -> c.pseudo(), c -> c.photoUrl())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Alice", null),
                        org.assertj.core.groups.Tuple.tuple(2, "Karim", "/api/photos/" + JETON_KARIM));
    }

    @Test
    @DisplayName("CA1 - un vainqueur porte aussi l'URL de sa photo")
    void doit_exposer_la_photo_url_d_un_vainqueur() {
        Inscription sophie = inscrire(3, "Sophie", StatutInscription.VAINQUEUR);
        annuairePhotos.jetons.put(sophie.compteId(), JETON_SOPHIE);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.vainqueurs()).extracting(c -> c.dossard(), c -> c.photoUrl())
                .containsExactly(org.assertj.core.groups.Tuple.tuple(3, "/api/photos/" + JETON_SOPHIE));
    }

    @Test
    @DisplayName("CA1 - un coureur de la même Course et un autre Compte ne se voient pas attribuer la photo d'un autre")
    void doit_attribuer_chaque_photo_a_son_propre_coureur() {
        Inscription karim = inscrire(2, "Karim", StatutInscription.EN_COURSE);
        Inscription sophie = inscrire(3, "Sophie", StatutInscription.EN_COURSE);
        annuairePhotos.jetons.put(karim.compteId(), JETON_KARIM);
        annuairePhotos.jetons.put(sophie.compteId(), JETON_SOPHIE);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).extracting(c -> c.photoUrl())
                .containsExactly("/api/photos/" + JETON_KARIM, "/api/photos/" + JETON_SOPHIE);
    }

    @Test
    @DisplayName("CA2 - un abandon n'a pas de photo et son Compte n'est pas demandé à l'annuaire des photos")
    void doit_ne_pas_demander_la_photo_d_un_coureur_en_abandon() {
        Inscription karim = inscrire(2, "Karim", StatutInscription.EN_COURSE);
        Inscription tom = inscrireAbandon(4, "Tom", MotifAbandon.MANUEL, 1);
        annuairePhotos.jetons.put(karim.compteId(), JETON_KARIM);
        annuairePhotos.jetons.put(tom.compteId(), JETON_TOM);

        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.abandons()).extracting(a -> a.dossard(), a -> a.pseudo()).containsExactly(
                org.assertj.core.groups.Tuple.tuple(4, "Tom"));
        assertThat(AbandonSuiviChamps.noms()).doesNotContain("photoUrl");
        assertThat(annuairePhotos.appels).hasSize(1);
        assertThat(annuairePhotos.appels.getFirst()).containsExactly(karim.compteId()).doesNotContain(tom.compteId());
    }

    @Test
    @DisplayName("CA3 - un seul appel à l'annuaire des photos, avec les identifiants de tous les coureurs en course et vainqueurs")
    void doit_lire_les_photos_en_un_seul_appel_pour_tous_les_comptes() {
        Inscription a = inscrire(1, "Alice", StatutInscription.EN_COURSE);
        Inscription b = inscrire(2, "Karim", StatutInscription.EN_COURSE);
        Inscription c = inscrire(3, "Sophie", StatutInscription.EN_COURSE);

        consulterSuivi.executer(X);

        assertThat(annuairePhotos.appels).hasSize(1);
        assertThat(annuairePhotos.appels.getFirst())
                .containsExactlyInAnyOrder(a.compteId(), b.compteId(), c.compteId());
    }

    @Test
    @DisplayName("CA3 - l'annuaire des photos n'est pas appelé pour une Course EN_PREPARATION")
    void doit_ne_pas_lire_les_photos_d_une_course_en_preparation() {
        UUID enPreparation = id(9);
        depotCourses.enregistrer(course(enPreparation, StatutCourse.EN_PREPARATION, null));
        inscrire(enPreparation, 1, "Alice", StatutInscription.EN_COURSE);

        var suivi = consulterSuivi.executer(enPreparation);

        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(annuairePhotos.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - l'annuaire des photos n'est pas appelé quand tous les coureurs sont en abandon")
    void doit_ne_pas_lire_les_photos_quand_tous_les_coureurs_sont_en_abandon() {
        inscrireAbandon(1, "Alice", MotifAbandon.HORS_DELAI, 1);
        inscrireAbandon(2, "Karim", MotifAbandon.MANUEL, 1);

        consulterSuivi.executer(X);

        assertThat(annuairePhotos.appels).isEmpty();
    }

    @Test
    @DisplayName("CA3 - l'annuaire des photos n'est pas appelé pour une Course sans Inscription")
    void doit_ne_pas_lire_les_photos_d_une_course_sans_inscription() {
        var suivi = consulterSuivi.executer(X);

        assertThat(suivi.coureursEnCourse()).isEmpty();
        assertThat(annuairePhotos.appels).isEmpty();
    }

    @Test
    @DisplayName("CA4 - Compte anonymisé (sans photo) ou absent de l'annuaire : photoUrl null, pseudo inchangé par 6.2 (« Compte inconnu » si non résolu)")
    void doit_donner_une_photo_url_nulle_a_un_compte_anonymise_ou_inconnu() {
        Inscription anonymise = inscrire(1, "Coureur anonyme", StatutInscription.EN_COURSE);
        Inscription inconnu = inscrireSansComptedansAnnuaires(2);

        var suivi = consulterSuivi.executer(X);

        assertThat(annuairePhotos.appels.getFirst()).contains(anonymise.compteId(), inconnu.compteId());
        assertThat(suivi.coureursEnCourse()).extracting(c -> c.dossard(), c -> c.pseudo(), c -> c.photoUrl())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, "Coureur anonyme", null),
                        org.assertj.core.groups.Tuple.tuple(2, "Compte inconnu", null));
    }

    // ---------- fabriques ----------

    private Inscription inscrire(int dossard, String pseudo, StatutInscription statut) {
        return inscrire(X, dossard, pseudo, statut);
    }

    private Inscription inscrire(UUID courseId, int dossard, String pseudo, StatutInscription statut) {
        return enregistrer(courseId, dossard, pseudo, statut, null, null);
    }

    private Inscription inscrireAbandon(int dossard, String pseudo, MotifAbandon motif, int boucle) {
        return enregistrer(X, dossard, pseudo, StatutInscription.ABANDON, motif, boucle);
    }

    private Inscription inscrireSansComptedansAnnuaires(int dossard) {
        return enregistrer(X, dossard, null, StatutInscription.EN_COURSE, null, null);
    }

    private Inscription enregistrer(UUID courseId, int dossard, String pseudo, StatutInscription statut,
                                    MotifAbandon motif, Integer boucle) {
        UUID compteId = UUID.randomUUID();
        if (pseudo != null) {
            annuairePseudos.pseudos.put(compteId, pseudo);
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

    /** Noms des composants du record AbandonSuivi : RG1 (aucune photo pour les abandons). */
    private static final class AbandonSuiviChamps {
        static List<String> noms() {
            Set<String> noms = new HashSet<>();
            for (var composant : ConsulterSuivi.AbandonSuivi.class.getRecordComponents()) {
                noms.add(composant.getName());
            }
            return List.copyOf(noms);
        }
    }
}
