package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.EFFACEMENT;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.SUPPRESSION;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.cle;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesPhoto.AnnulationNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.ConsultationFactice;
import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.DesaffectationFactice;
import fr.backyard.tracker.comptes.application.DoublesPhoto.EncodeurPrefixe;
import fr.backyard.tracker.comptes.application.DoublesPhoto.InvalidationNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.RegistreNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.application.SupprimerCompteCoureur.Commande;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException;
import fr.backyard.tracker.comptes.domaine.BenevoleAffecteCourseEnCoursException.CourseEnCours;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DernierAdminActifNonSupprimableException;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Effacement de la photo aux trois suppressions (10.1a, CA5, RG9) : coureur (3.6), bénévole (9.3 / 9.6a), admin (9.4).
 * Constructeurs attendus : le composant {@code EffacementPhoto} est le DERNIER paramètre de chaque cas d'usage.
 * Hors transaction, « après validation » est immédiat : le journal vérifie que l'effacement suit l'écriture en base.
 */
class SuppressionComptePhotoTest {

    private static final String MOT_DE_PASSE = "un-mot-de-passe-12";
    private static final ReferencePhoto PHOTO = new ReferencePhoto("0123456789abcdef0123456789abcdef", FormatImage.JPEG);
    private static final String FICHIER = cle(PHOTO.jeton(), PHOTO.format());

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final EffacementPhoto effacement = new EffacementPhoto(stockage);
    private final ConsultationFactice inscriptions = new ConsultationFactice();
    private final DesaffectationFactice desaffectation = new DesaffectationFactice();
    private final InvalidationNeutre sessions = new InvalidationNeutre();

    private final SupprimerCompteCoureur supprimerCoureur = new SupprimerCompteCoureur(depot, new EncodeurPrefixe(),
            new RegistreNeutre(), PolitiqueBlocage.parDefaut(), new AnnulationNeutre(), sessions,
            Clock.fixed(DoublesPhoto.T, ZoneOffset.UTC), effacement);
    private final SupprimerBenevole supprimerBenevole = new SupprimerBenevole(depot, desaffectation, sessions,
            new RegistreNeutre(), PolitiqueBlocage.parDefaut(), new AnnulationNeutre(), inscriptions, effacement);
    private final SupprimerAdmin supprimerAdmin = new SupprimerAdmin(depot, sessions, new RegistreNeutre(),
            PolitiqueBlocage.parDefaut(), new AnnulationNeutre(), inscriptions, effacement);

    private Compte avecPhoto(String pseudo, Role role) {
        Compte compte = compte(depot, pseudo, role, PHOTO);
        stockage.poserFichier(PHOTO, png());
        journal.clear();
        return compte;
    }

    private void assertPhotoIntacte(Compte compte) {
        assertThat(stockage.effacements).isEmpty();
        assertThat(stockage.fichiers).containsOnlyKeys(FICHIER);
        assertThat(depot.comptes.get(compte.id()).photo()).isEqualTo(PHOTO);
    }

    // ---------- coureur (3.6) ----------

    @Test
    @DisplayName("CA5 - suppression d'un coureur : Compte anonymisé sans photo, puis fichier effacé après l'enregistrement")
    void doit_effacer_la_photo_a_la_suppression_d_un_coureur() {
        Compte coureur = avecPhoto("Karim", Role.COUREUR);

        supprimerCoureur.executer(new Commande(coureur.id(), MOT_DE_PASSE));

        assertThat(depot.comptes.get(coureur.id()).photo()).isNull();
        assertThat(stockage.fichiers).isEmpty();
        assertThat(journal).containsExactly(MISE_A_JOUR, EFFACEMENT + FICHIER);
    }

    @Test
    @DisplayName("CA5 - suppression d'un coureur sans photo : aucun appel au stockage")
    void doit_ne_rien_effacer_pour_un_coureur_sans_photo() {
        Compte coureur = compte(depot, "Alice", Role.COUREUR, null);

        supprimerCoureur.executer(new Commande(coureur.id(), MOT_DE_PASSE));

        assertThat(stockage.appels()).isZero();
    }

    @Test
    @DisplayName("CA5 - mot de passe faux : la photo et le fichier restent intacts")
    void doit_garder_la_photo_quand_le_mot_de_passe_est_faux() {
        Compte coureur = avecPhoto("Karim", Role.COUREUR);

        assertThatThrownBy(() -> supprimerCoureur.executer(new Commande(coureur.id(), "secret-de-test-123")))
                .isInstanceOf(MotDePasseActuelIncorrectException.class);

        assertPhotoIntacte(coureur);
    }

    @Test
    @DisplayName("CA5 - échec de l'enregistrement du Compte : aucun effacement du fichier")
    void doit_garder_le_fichier_quand_l_enregistrement_du_compte_echoue() {
        Compte coureur = avecPhoto("Karim", Role.COUREUR);
        depot.panneMiseAJour = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> supprimerCoureur.executer(new Commande(coureur.id(), MOT_DE_PASSE)))
                .isSameAs(depot.panneMiseAJour);

        assertPhotoIntacte(coureur);
    }

    @Test
    @DisplayName("CA5 - échec d'effacement du fichier : la suppression du coureur réussit quand même")
    void doit_reussir_la_suppression_d_un_coureur_malgre_un_echec_d_effacement() {
        Compte coureur = avecPhoto("Karim", Role.COUREUR);
        stockage.panneEffacement = new IllegalStateException("fichier absent");

        supprimerCoureur.executer(new Commande(coureur.id(), MOT_DE_PASSE));

        assertThat(depot.comptes.get(coureur.id()).estAnonyme()).isTrue();
        assertThat(sessions.fermees).containsExactly(coureur.id());
    }

    // ---------- bénévole (9.3, 9.6a) ----------

    @Test
    @DisplayName("CA5 - suppression physique d'un bénévole ex-coureur sans Inscription : ligne supprimée puis fichier effacé")
    void doit_effacer_la_photo_a_la_suppression_physique_d_un_benevole() {
        Compte benevole = avecPhoto("Leo", Role.BENEVOLE);

        supprimerBenevole.executer(UUID.randomUUID(), benevole.id());

        assertThat(depot.comptes).doesNotContainKey(benevole.id());
        assertThat(stockage.fichiers).isEmpty();
        assertThat(journal).containsExactly(SUPPRESSION, EFFACEMENT + FICHIER);
    }

    @Test
    @DisplayName("CA5 - anonymisation d'un bénévole ex-coureur avec Inscription : Compte sans photo, fichier effacé")
    void doit_effacer_la_photo_a_l_anonymisation_d_un_benevole() {
        Compte benevole = avecPhoto("Leo", Role.BENEVOLE);
        inscriptions.avecInscriptions.add(benevole.id());

        supprimerBenevole.executer(UUID.randomUUID(), benevole.id());

        assertThat(depot.comptes.get(benevole.id()).photo()).isNull();
        assertThat(stockage.fichiers).isEmpty();
        assertThat(journal).containsExactly(MISE_A_JOUR, EFFACEMENT + FICHIER);
    }

    @Test
    @DisplayName("CA5 - bénévole affecté à une Course EN_COURS (refus 409) : photo et fichier intacts")
    void doit_garder_la_photo_quand_la_suppression_du_benevole_est_refusee() {
        Compte benevole = avecPhoto("Leo", Role.BENEVOLE);
        desaffectation.refus = new BenevoleAffecteCourseEnCoursException(
                List.of(new CourseEnCours(UUID.randomUUID(), "Backyard de l'Automne")));

        assertThatThrownBy(() -> supprimerBenevole.executer(UUID.randomUUID(), benevole.id()))
                .isSameAs(desaffectation.refus);

        assertPhotoIntacte(benevole);
    }

    @Test
    @DisplayName("CA5 - Compte qui n'est pas un bénévole (refus) : la photo du Compte visé n'est pas touchée")
    void doit_garder_la_photo_d_un_compte_qui_n_est_pas_un_benevole() {
        Compte coureur = avecPhoto("Karim", Role.COUREUR);

        assertThatThrownBy(() -> supprimerBenevole.executer(UUID.randomUUID(), coureur.id()))
                .isInstanceOf(fr.backyard.tracker.comptes.domaine.CompteNonGerableException.class);

        assertPhotoIntacte(coureur);
    }

    // ---------- admin (9.4) ----------

    @Test
    @DisplayName("CA5 - suppression physique d'un admin ex-coureur sans Inscription : ligne supprimée puis fichier effacé")
    void doit_effacer_la_photo_a_la_suppression_physique_d_un_admin() {
        compte(depot, "Zoe", Role.ADMIN, null);
        Compte admin = avecPhoto("Nadia", Role.ADMIN);

        supprimerAdmin.executer(UUID.randomUUID(), admin.id());

        assertThat(depot.comptes).doesNotContainKey(admin.id());
        assertThat(stockage.fichiers).isEmpty();
        assertThat(journal).containsExactly(SUPPRESSION, EFFACEMENT + FICHIER);
    }

    @Test
    @DisplayName("CA5 - anonymisation d'un admin ex-coureur avec Inscription : Compte sans photo, fichier effacé")
    void doit_effacer_la_photo_a_l_anonymisation_d_un_admin() {
        compte(depot, "Zoe", Role.ADMIN, null);
        Compte admin = avecPhoto("Nadia", Role.ADMIN);
        inscriptions.avecInscriptions.add(admin.id());

        supprimerAdmin.executer(UUID.randomUUID(), admin.id());

        assertThat(depot.comptes.get(admin.id()).photo()).isNull();
        assertThat(stockage.fichiers).isEmpty();
        assertThat(journal).containsExactly(MISE_A_JOUR, EFFACEMENT + FICHIER);
    }

    @Test
    @DisplayName("CA5 - dernier admin actif (refus 409) : photo et fichier intacts")
    void doit_garder_la_photo_quand_la_suppression_de_l_admin_est_refusee() {
        Compte admin = avecPhoto("Nadia", Role.ADMIN);

        assertThatThrownBy(() -> supprimerAdmin.executer(UUID.randomUUID(), admin.id()))
                .isInstanceOf(DernierAdminActifNonSupprimableException.class);

        assertPhotoIntacte(admin);
    }

    @Test
    @DisplayName("CA5 - échec d'effacement du fichier : la suppression de l'admin réussit quand même")
    void doit_reussir_la_suppression_d_un_admin_malgre_un_echec_d_effacement() {
        compte(depot, "Zoe", Role.ADMIN, null);
        Compte admin = avecPhoto("Nadia", Role.ADMIN);
        stockage.panneEffacement = new IllegalStateException("droits insuffisants");

        supprimerAdmin.executer(UUID.randomUUID(), admin.id());

        assertThat(depot.comptes).doesNotContainKey(admin.id());
        assertThat(sessions.fermees).containsExactly(admin.id());
    }
}
