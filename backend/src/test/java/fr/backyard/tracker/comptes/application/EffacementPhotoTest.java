package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.EFFACEMENT;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.cle;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Composant partagé EffacementPhoto (10.1a, CA5, RG9). {@code effacerApresValidation(Compte)} reçoit le Compte tel qu'il
 * était AVANT sa suppression ou son anonymisation et planifie l'effacement de son fichier après validation (hors
 * transaction : immédiatement). Une panne d'effacement n'est jamais propagée.
 */
class EffacementPhotoTest {

    private static final ReferencePhoto PHOTO = new ReferencePhoto("0123456789abcdef0123456789abcdef", FormatImage.WEBP);

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final EffacementPhoto effacement = new EffacementPhoto(stockage);

    @Test
    @DisplayName("CA5 - efface le fichier de la photo du Compte, une seule fois")
    void doit_effacer_le_fichier_de_la_photo() {
        Compte coureur = compte(depot, "Karim", Role.COUREUR, PHOTO);
        stockage.poserFichier(PHOTO, png());
        journal.clear();

        effacement.effacerApresValidation(coureur);

        assertThat(journal).containsExactly(EFFACEMENT + cle(PHOTO.jeton(), PHOTO.format()));
        assertThat(stockage.fichiers).isEmpty();
    }

    @Test
    @DisplayName("CA5 - un Compte sans photo : aucun appel au stockage")
    void doit_ne_rien_faire_sans_photo() {
        Compte coureur = compte(depot, "Alice", Role.COUREUR, null);

        effacement.effacerApresValidation(coureur);

        assertThat(stockage.appels()).isZero();
    }

    @Test
    @DisplayName("CA5 - un échec d'effacement est avalé : aucune exception propagée")
    void doit_avaler_un_echec_d_effacement() {
        Compte coureur = compte(depot, "Karim", Role.COUREUR, PHOTO);
        stockage.panneEffacement = new IllegalStateException("fichier absent");

        assertThatCode(() -> effacement.effacerApresValidation(coureur)).doesNotThrowAnyException();

        assertThat(stockage.effacements).hasSize(1);
    }

    @Test
    @DisplayName("CA5 - hors transaction, l'effacement a lieu immédiatement ; une autre photo n'est pas touchée")
    void doit_effacer_uniquement_la_photo_du_compte() {
        ReferencePhoto autre = new ReferencePhoto("ffffffffffffffffffffffffffffffff", FormatImage.PNG);
        stockage.poserFichier(autre, png());
        stockage.poserFichier(PHOTO, png());
        Compte coureur = compte(depot, "Karim", Role.COUREUR, PHOTO);

        effacement.effacerApresValidation(coureur);

        assertThat(stockage.fichiers).containsOnlyKeys(cle(autre.jeton(), autre.format()));
    }
}
