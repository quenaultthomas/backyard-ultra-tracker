package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.cle;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.domaine.PhotoIntrouvableException;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, RG3 / CA4 : lecture scindée en deux étapes.
 * Signatures attendues sur {@link LirePhoto} :
 * <ul>
 * <li>{@code ReferencePhoto resoudre(String jeton)} : contrôle de forme, une recherche au dépôt, aucun accès au
 * stockage ; lève {@link PhotoIntrouvableException} ;</li>
 * <li>{@code LirePhoto.PhotoLue lire(ReferencePhoto reference)} : un appel au stockage ; fichier manquant lève
 * {@link PhotoIntrouvableException}.</li>
 * </ul>
 */
class LirePhotoResolutionTest {

    private static final String JETON = "3f2a9c1d7b64e8a05c21d4f6b9a7e310";

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final LirePhoto lire = new LirePhoto(depot, stockage);

    @Test
    @DisplayName("CA4 - résoudre un jeton connu rend le jeton et le format sans aucun appel au stockage")
    void doit_resoudre_le_jeton_sans_toucher_au_stockage() {
        compte(depot, "Karim", Role.COUREUR, new ReferencePhoto(JETON, FormatImage.JPEG));

        ReferencePhoto reference = lire.resoudre(JETON);

        assertThat(reference.jeton()).isEqualTo(JETON);
        assertThat(reference.format()).isEqualTo(FormatImage.JPEG);
        assertThat(stockage.appels()).isZero();
        assertThat(depot.recherchesParJeton).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4 - résoudre un jeton mal formé lève PhotoIntrouvableException sans appel au dépôt ni au stockage")
    void doit_refuser_un_jeton_mal_forme_sans_aucun_acces() {
        assertThatThrownBy(() -> lire.resoudre("../etc/passwd")).isInstanceOf(PhotoIntrouvableException.class);
        assertThatThrownBy(() -> lire.resoudre(null)).isInstanceOf(PhotoIntrouvableException.class);

        assertThat(depot.recherchesParJeton).isZero();
        assertThat(stockage.appels()).isZero();
    }

    @Test
    @DisplayName("CA4 - résoudre un jeton inconnu lève PhotoIntrouvableException après un seul appel au dépôt, sans stockage")
    void doit_refuser_un_jeton_inconnu_apres_une_seule_recherche() {
        assertThatThrownBy(() -> lire.resoudre(JETON)).isInstanceOf(PhotoIntrouvableException.class);

        assertThat(depot.recherchesParJeton).isEqualTo(1);
        assertThat(stockage.appels()).isZero();
    }

    @Test
    @DisplayName("CA4 - lire à partir de la référence résolue appelle le stockage exactement une fois avec jeton et format")
    void doit_lire_les_octets_en_un_seul_appel_au_stockage() {
        var reference = new ReferencePhoto(JETON, FormatImage.JPEG);
        compte(depot, "Karim", Role.COUREUR, reference);
        byte[] octets = jpeg();
        stockage.poserFichier(reference, octets);

        LirePhoto.PhotoLue lue = lire.lire(lire.resoudre(JETON));

        assertThat(lue.octets()).isEqualTo(octets);
        assertThat(lue.jeton()).isEqualTo(JETON);
        assertThat(lue.format()).isEqualTo(FormatImage.JPEG);
        assertThat(stockage.lectures).isEqualTo(1);
        assertThat(depot.recherchesParJeton).isEqualTo(1);
        assertThat(stockage.fichiers).containsOnlyKeys(cle(JETON, FormatImage.JPEG));
    }

    @Test
    @DisplayName("CA4 - lire une référence dont le fichier manque lève PhotoIntrouvableException")
    void doit_refuser_la_lecture_quand_le_fichier_manque() {
        var reference = new ReferencePhoto(JETON, FormatImage.PNG);
        compte(depot, "Karim", Role.COUREUR, reference);

        assertThatThrownBy(() -> lire.lire(lire.resoudre(JETON))).isInstanceOf(PhotoIntrouvableException.class);

        assertThat(stockage.lectures).isEqualTo(1);
    }
}
