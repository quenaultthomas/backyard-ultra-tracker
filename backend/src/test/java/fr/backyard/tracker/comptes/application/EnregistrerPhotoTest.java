package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.EFFACEMENT;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.ECRITURE;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.MISE_A_JOUR;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.cle;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static fr.backyard.tracker.courses.OctetsDeLogo.pngDeTaille;
import static fr.backyard.tracker.courses.OctetsDeLogo.TAILLE_MAXIMALE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.JetonPhoto;
import fr.backyard.tracker.comptes.domaine.PhotoInvalideException;
import fr.backyard.tracker.comptes.domaine.PhotoInvalideException.Motif;
import fr.backyard.tracker.comptes.domaine.PhotoNonAutoriseeException;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.io.UncheckedIOException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Cas d'usage EnregistrerPhoto (10.1a, CA4, RG3, RG5). Hors transaction Spring, « après validation » signifie
 * « immédiatement après la mise à jour du Compte » : le journal vérifie cet ordre.
 */
class EnregistrerPhotoTest {

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final EnregistrerPhoto enregistrer = new EnregistrerPhoto(depot, stockage, new EffacementPhoto(stockage));

    private static final ReferencePhoto ANCIENNE = new ReferencePhoto("0123456789abcdef0123456789abcdef", FormatImage.PNG);

    private Compte coureurSansPhoto() {
        return compte(depot, "Karim", Role.COUREUR, null);
    }

    private Compte coureurAvecAnciennePhoto() {
        Compte coureur = compte(depot, "Sophie", Role.COUREUR, ANCIENNE);
        stockage.poserFichier(ANCIENNE, png());
        journal.clear();
        return coureur;
    }

    // ---------- succès ----------

    @Test
    @DisplayName("CA4 - succès : le fichier est écrit sous <jeton>.<ext> du format détecté et le jeton est enregistré")
    void doit_ecrire_le_fichier_sous_le_jeton_et_l_extension_du_format_detecte() {
        Compte coureur = coureurSansPhoto();
        byte[] octets = jpeg();

        String jeton = enregistrer.executer(coureur.id(), octets);

        assertThat(jeton).matches("^[0-9a-f]{32}$").isNotEqualTo(coureur.id().toString());
        assertThat(stockage.fichiers).containsOnlyKeys(jeton + ".jpg");
        assertThat(stockage.fichiers.get(jeton + ".jpg")).isEqualTo(octets);
        assertThat(depot.comptes.get(coureur.id()).photo()).isEqualTo(new ReferencePhoto(jeton, FormatImage.JPEG));
    }

    @Test
    @DisplayName("CA4 - l'extension suit le format détecté par les octets : un PNG est écrit en .png")
    void doit_utiliser_l_extension_png_pour_un_png() {
        Compte coureur = coureurSansPhoto();

        String jeton = enregistrer.executer(coureur.id(), png());

        assertThat(stockage.fichiers).containsOnlyKeys(jeton + ".png");
        assertThat(depot.comptes.get(coureur.id()).photo().format()).isEqualTo(FormatImage.PNG);
    }

    @Test
    @DisplayName("CA4 - sans ancienne photo : écriture puis mise à jour, aucun effacement")
    void doit_ne_rien_effacer_sans_ancienne_photo() {
        Compte coureur = coureurSansPhoto();

        String jeton = enregistrer.executer(coureur.id(), png());

        assertThat(journal).containsExactly(ECRITURE + jeton + ".png", MISE_A_JOUR);
        assertThat(stockage.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA4 - avec une ancienne photo : écriture, mise à jour, puis effacement de l'ancien fichier en dernier")
    void doit_effacer_l_ancien_fichier_apres_la_mise_a_jour() {
        Compte coureur = coureurAvecAnciennePhoto();

        String jeton = enregistrer.executer(coureur.id(), jpeg());

        assertThat(journal).containsExactly(
                ECRITURE + jeton + ".jpg", MISE_A_JOUR, EFFACEMENT + cle(ANCIENNE.jeton(), ANCIENNE.format()));
        assertThat(stockage.fichiers).containsOnlyKeys(jeton + ".jpg");
        assertThat(jeton).isNotEqualTo(ANCIENNE.jeton());
    }

    @Test
    @DisplayName("CA4 - le jeton change à chaque remplacement, même avec le même fichier : un seul fichier subsiste")
    void doit_generer_un_nouveau_jeton_a_chaque_envoi_et_ne_garder_qu_un_fichier() {
        Compte coureur = coureurSansPhoto();
        byte[] octets = png();

        String premier = enregistrer.executer(coureur.id(), octets);
        String second = enregistrer.executer(coureur.id(), octets);

        assertThat(second).isNotEqualTo(premier);
        assertThat(stockage.fichiers).containsOnlyKeys(second + ".png");
        assertThat(depot.comptes.get(coureur.id()).photo().jeton()).isEqualTo(second);
    }

    @Test
    @DisplayName("CA4 - exactement 2 097 152 octets : accepté")
    void doit_accepter_une_photo_de_2_mo_pile() {
        Compte coureur = coureurSansPhoto();

        String jeton = enregistrer.executer(coureur.id(), pngDeTaille(TAILLE_MAXIMALE));

        assertThat(stockage.fichiers.get(jeton + ".png")).hasSize(TAILLE_MAXIMALE);
    }

    // ---------- échecs ----------

    @Test
    @DisplayName("CA4 - erreur d'écriture : exception propagée, Compte et ancienne photo intacts, ancien fichier conservé")
    void doit_conserver_l_ancienne_photo_quand_l_ecriture_echoue() {
        Compte coureur = coureurAvecAnciennePhoto();
        stockage.panneEcriture = new UncheckedIOException(new IOException("disque plein"));

        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), jpeg())).isSameAs(stockage.panneEcriture);

        assertThat(depot.misesAJour).isZero();
        assertThat(depot.comptes.get(coureur.id()).photo()).isEqualTo(ANCIENNE);
        assertThat(stockage.fichiers).containsOnlyKeys(cle(ANCIENNE.jeton(), ANCIENNE.format()));
        assertThat(stockage.effacements).doesNotContain(cle(ANCIENNE.jeton(), ANCIENNE.format()));
    }

    @Test
    @DisplayName("CA4 - mise à jour du Compte en échec (ou transaction annulée) : fichier neuf effacé, ancienne photo intacte")
    void doit_effacer_le_fichier_neuf_et_garder_l_ancienne_photo_quand_la_mise_a_jour_echoue() {
        Compte coureur = coureurAvecAnciennePhoto();
        depot.panneMiseAJour = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), jpeg())).isSameAs(depot.panneMiseAJour);

        assertThat(stockage.fichiers).containsOnlyKeys(cle(ANCIENNE.jeton(), ANCIENNE.format()));
        assertThat(stockage.effacements).hasSize(1).allMatch(nom -> nom.endsWith(".jpg"));
        assertThat(depot.comptes.get(coureur.id()).photo()).isEqualTo(ANCIENNE);
    }

    @Test
    @DisplayName("CA4 - échec de mise à jour d'un coureur sans photo : fichier neuf effacé, rien ne subsiste")
    void doit_ne_rien_laisser_quand_la_mise_a_jour_echoue_sans_ancienne_photo() {
        Compte coureur = coureurSansPhoto();
        depot.panneMiseAJour = new IllegalStateException("panne du dépôt");

        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), png())).isSameAs(depot.panneMiseAJour);

        assertThat(stockage.fichiers).isEmpty();
        assertThat(depot.comptes.get(coureur.id()).photo()).isNull();
    }

    @Test
    @DisplayName("CA4 - Compte BENEVOLE : PhotoNonAutoriseeException, aucune écriture, Compte inchangé")
    void doit_refuser_un_benevole_sans_ecriture() {
        Compte benevole = compte(depot, "Leo", Role.BENEVOLE, null);

        assertThatThrownBy(() -> enregistrer.executer(benevole.id(), png()))
                .isInstanceOf(PhotoNonAutoriseeException.class);

        assertThat(stockage.appels()).isZero();
        assertThat(depot.misesAJour).isZero();
        assertThat(depot.comptes.get(benevole.id()).photo()).isNull();
    }

    @Test
    @DisplayName("CA4 - BENEVOLE ex-coureur qui a une photo : remplacement refusé, ancienne photo et fichier intacts")
    void doit_refuser_le_remplacement_par_un_benevole_ex_coureur() {
        Compte benevole = compte(depot, "Leo", Role.BENEVOLE, ANCIENNE);
        stockage.poserFichier(ANCIENNE, png());

        assertThatThrownBy(() -> enregistrer.executer(benevole.id(), jpeg()))
                .isInstanceOf(PhotoNonAutoriseeException.class);

        assertThat(stockage.ecritures).isEmpty();
        assertThat(stockage.effacements).isEmpty();
        assertThat(depot.comptes.get(benevole.id()).photo()).isEqualTo(ANCIENNE);
    }

    @Test
    @DisplayName("CA4 - Compte ADMIN : PhotoNonAutoriseeException, aucune écriture")
    void doit_refuser_un_admin_sans_ecriture() {
        Compte admin = compte(depot, "Nadia", Role.ADMIN, null);

        assertThatThrownBy(() -> enregistrer.executer(admin.id(), png()))
                .isInstanceOf(PhotoNonAutoriseeException.class);

        assertThat(stockage.appels()).isZero();
    }

    @Test
    @DisplayName("CA4 - fichier invalide : exception de domaine, sans écriture ni changement du Compte")
    void doit_refuser_un_fichier_invalide_sans_ecriture() {
        Compte coureur = coureurAvecAnciennePhoto();

        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), ascii("GIF89a" + "x".repeat(50))))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.FORMAT_INVALIDE));

        assertThat(stockage.ecritures).isEmpty();
        assertThat(stockage.effacements).isEmpty();
        assertThat(depot.misesAJour).isZero();
        assertThat(depot.comptes.get(coureur.id()).photo()).isEqualTo(ANCIENNE);
    }

    @Test
    @DisplayName("CA4 - fichier absent ou vide : REQUISE ; trop volumineux : TROP_VOLUMINEUSE ; rien n'est écrit")
    void doit_refuser_un_fichier_vide_ou_trop_gros_sans_ecriture() {
        Compte coureur = coureurSansPhoto();

        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), new byte[0]))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.REQUISE));
        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), null))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.REQUISE));
        assertThatThrownBy(() -> enregistrer.executer(coureur.id(), pngDeTaille(TAILLE_MAXIMALE + 1)))
                .isInstanceOfSatisfying(PhotoInvalideException.class,
                        e -> assertThat(e.motif()).isEqualTo(Motif.TROP_VOLUMINEUSE));

        assertThat(stockage.appels()).isZero();
        assertThat(depot.misesAJour).isZero();
    }

    @Test
    @DisplayName("CA4 - jeton enregistré en base et jeton écrit sur disque sont le même, valide selon JetonPhoto")
    void doit_utiliser_un_seul_jeton_valide_pour_la_base_et_le_fichier() {
        Compte coureur = coureurSansPhoto();

        String jeton = enregistrer.executer(coureur.id(), png());

        assertThat(JetonPhoto.estValide(jeton)).isTrue();
        assertThat(stockage.ecritures).containsExactly(jeton + ".png");
        assertThat(depot.comptes.get(coureur.id()).photo().jeton()).isEqualTo(jeton);
    }

    @Test
    @DisplayName("CA4 - la photo d'un autre coureur n'est jamais touchée")
    void doit_isoler_les_photos_des_coureurs() {
        Compte autre = compte(depot, "Alice", Role.COUREUR, new ReferencePhoto("ffffffffffffffffffffffffffffffff", FormatImage.WEBP));
        stockage.poserFichier(autre.photo(), png());
        Compte coureur = coureurSansPhoto();

        enregistrer.executer(coureur.id(), png());

        assertThat(depot.comptes.get(autre.id()).photo()).isEqualTo(autre.photo());
        assertThat(stockage.fichiers).containsKey("ffffffffffffffffffffffffffffffff.webp");
        assertThat(stockage.effacements).isEmpty();
    }

    @Test
    @DisplayName("CA4 - un Compte introuvable lève CompteIntrouvableOuInutilisableException sans écriture")
    void doit_refuser_un_compte_introuvable_sans_ecriture() {
        assertThatThrownBy(() -> enregistrer.executer(UUID.randomUUID(), png()))
                .isInstanceOf(fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException.class);

        assertThat(stockage.appels()).isZero();
    }
}
