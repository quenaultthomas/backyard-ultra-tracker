package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.PhotoIntrouvableException;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Cas d'usage LirePhoto (10.1a, CA5, RG7). {@code executer(jeton)} rend un {@link LirePhoto.PhotoLue} (jeton, format,
 * octets) ; le jeton est contrôlé avant tout accès au dépôt ou au stockage.
 */
class LirePhotoTest {

    private static final String JETON = "3f2a9c1d7b64e8a05c21d4f6b9a7e310";

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final LirePhoto lire = new LirePhoto(depot, stockage);

    private Compte coureurAvecPhoto(FormatImage format, byte[] octets) {
        Compte compte = compte(depot, "Karim", Role.COUREUR, new ReferencePhoto(JETON, format));
        stockage.poserFichier(compte.photo(), octets);
        return compte;
    }

    @Test
    @DisplayName("CA5 - un jeton connu rend les octets, le format et le jeton")
    void doit_lire_la_photo_d_un_jeton_connu() {
        byte[] octets = jpeg();
        coureurAvecPhoto(FormatImage.JPEG, octets);

        LirePhoto.PhotoLue lue = lire.executer(JETON);

        assertThat(lue.octets()).isEqualTo(octets);
        assertThat(lue.format()).isEqualTo(FormatImage.JPEG);
        assertThat(lue.jeton()).isEqualTo(JETON);
    }

    @ParameterizedTest(name = "CA5 - jeton mal formé « {0} » : introuvable sans aucun accès")
    @NullAndEmptySource
    @ValueSource(strings = {
            "..", "../../etc/passwd", "%2e%2e%2f", "abc",
            "3f2a9c1d7b64e8a05c21d4f6b9a7e31",        // 31 caractères
            "3f2a9c1d7b64e8a05c21d4f6b9a7e3100",      // 33 caractères
            "3F2A9C1D7B64E8A05C21D4F6B9A7E310",       // majuscules
            "3f2a9c1d7b64e8a05c21d4f6b9a7e310.png",   // extension
            "3f2a9c1d7b64e8a05c21d4f6b9a7e31\u0000",  // octet nul
            "3f2a9c1d7b64e8a05c21d4f6b9a7e31/",       // séparateur
            "3f2a9c1d7b64e8a05c21d4f6b9a7e31\\",      // séparateur Windows
            "zz2a9c1d7b64e8a05c21d4f6b9a7e310"        // non hexadécimal
    })
    @DisplayName("CA5 - un jeton mal formé lève PhotoIntrouvableException sans accès au dépôt ni au stockage")
    void doit_refuser_un_jeton_mal_forme_sans_acces(String jeton) {
        coureurAvecPhoto(FormatImage.PNG, png());

        assertThatThrownBy(() -> lire.executer(jeton)).isInstanceOf(PhotoIntrouvableException.class);

        assertThat(depot.recherchesParJeton).isZero();
        assertThat(stockage.lectures).isZero();
    }

    @Test
    @DisplayName("CA5 - l'identifiant du Compte n'est jamais accepté comme jeton")
    void doit_refuser_l_identifiant_du_compte_comme_jeton() {
        Compte compte = coureurAvecPhoto(FormatImage.PNG, png());

        assertThatThrownBy(() -> lire.executer(compte.id().toString())).isInstanceOf(PhotoIntrouvableException.class);
        assertThatThrownBy(() -> lire.executer(compte.id().toString().replace("-", "")))
                .isInstanceOf(PhotoIntrouvableException.class);
    }

    @Test
    @DisplayName("CA5 - un jeton inconnu (32 hexadécimaux bien formés) est introuvable")
    void doit_refuser_un_jeton_inconnu() {
        coureurAvecPhoto(FormatImage.PNG, png());

        assertThatThrownBy(() -> lire.executer("00112233445566778899aabbccddeeff"))
                .isInstanceOf(PhotoIntrouvableException.class);

        assertThat(stockage.lectures).isZero();
    }

    @Test
    @DisplayName("CA5 - une photo retirée est introuvable avec son ancien jeton")
    void doit_refuser_une_photo_retiree() {
        Compte compte = coureurAvecPhoto(FormatImage.PNG, png());
        depot.comptes.put(compte.id(), compte.retirerPhoto());

        assertThatThrownBy(() -> lire.executer(JETON)).isInstanceOf(PhotoIntrouvableException.class);
    }

    @Test
    @DisplayName("CA5 - un Compte anonymisé n'a plus de photo : son ancien jeton est introuvable")
    void doit_refuser_la_photo_d_un_compte_anonymise() {
        Compte compte = coureurAvecPhoto(FormatImage.PNG, png());
        depot.comptes.put(compte.id(), compte.anonymiser());

        assertThatThrownBy(() -> lire.executer(JETON)).isInstanceOf(PhotoIntrouvableException.class);
    }

    @Test
    @DisplayName("CA5 - une photo remplacée : l'ancien jeton est introuvable, le nouveau est servi")
    void doit_refuser_l_ancien_jeton_apres_remplacement() {
        Compte compte = coureurAvecPhoto(FormatImage.PNG, png());
        String nouveau = "00112233445566778899aabbccddeeff";
        depot.comptes.put(compte.id(), compte.avecPhoto(nouveau, FormatImage.JPEG));
        stockage.poserFichier(new ReferencePhoto(nouveau, FormatImage.JPEG), jpeg());

        assertThatThrownBy(() -> lire.executer(JETON)).isInstanceOf(PhotoIntrouvableException.class);
        assertThat(lire.executer(nouveau).format()).isEqualTo(FormatImage.JPEG);
    }

    @Test
    @DisplayName("CA5 - le jeton est en base mais le fichier manque : introuvable (la base ne suffit pas)")
    void doit_refuser_quand_le_fichier_manque() {
        Compte compte = compte(depot, "Karim", Role.COUREUR, new ReferencePhoto(JETON, FormatImage.PNG));

        assertThatThrownBy(() -> lire.executer(JETON)).isInstanceOf(PhotoIntrouvableException.class);

        assertThat(depot.comptes.get(compte.id()).photo()).isNotNull();
    }

    @Test
    @DisplayName("CA5 - un BENEVOLE ex-coureur garde une photo toujours servie (RG10)")
    void doit_servir_la_photo_d_un_benevole_ex_coureur() {
        compte(depot, "Leo", Role.BENEVOLE, new ReferencePhoto(JETON, FormatImage.PNG));
        stockage.poserFichier(new ReferencePhoto(JETON, FormatImage.PNG), png());

        assertThat(lire.executer(JETON).format()).isEqualTo(FormatImage.PNG);
    }
}
