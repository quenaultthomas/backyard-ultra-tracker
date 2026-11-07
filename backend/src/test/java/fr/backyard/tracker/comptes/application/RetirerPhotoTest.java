package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.EFFACEMENT;
import static fr.backyard.tracker.comptes.application.DoublesPhoto.MISE_A_JOUR;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Cas d'usage RetirerPhoto (10.1a, CA5, RG1, RG6). */
class RetirerPhotoTest {

    private static final ReferencePhoto PHOTO = new ReferencePhoto("0123456789abcdef0123456789abcdef", FormatImage.JPEG);

    private final List<String> journal = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(journal);
    private final StockageEnMemoire stockage = new StockageEnMemoire(journal);
    private final RetirerPhoto retirer = new RetirerPhoto(depot, new EffacementPhoto(stockage));

    private Compte avecPhoto(Role role) {
        Compte compte = compte(depot, "Karim", role, PHOTO);
        stockage.poserFichier(PHOTO, png());
        return compte;
    }

    @Test
    @DisplayName("CA5 - retrait avec photo : jeton nul puis effacement du fichier après la mise à jour")
    void doit_remettre_le_jeton_a_nul_puis_effacer_le_fichier() {
        Compte coureur = avecPhoto(Role.COUREUR);

        retirer.executer(coureur.id());

        assertThat(depot.comptes.get(coureur.id()).photo()).isNull();
        assertThat(journal).containsExactly(MISE_A_JOUR, EFFACEMENT + cle(PHOTO.jeton(), PHOTO.format()));
        assertThat(stockage.fichiers).isEmpty();
    }

    @Test
    @DisplayName("CA5 - retrait sans photo : rien n'est modifié ni effacé, pas d'erreur (idempotent)")
    void doit_ne_rien_faire_sans_photo() {
        Compte coureur = compte(depot, "Alice", Role.COUREUR, null);

        assertThatCode(() -> retirer.executer(coureur.id())).doesNotThrowAnyException();
        assertThatCode(() -> retirer.executer(coureur.id())).doesNotThrowAnyException();

        assertThat(stockage.effacements).isEmpty();
        assertThat(depot.comptes.get(coureur.id()).photo()).isNull();
    }

    @ParameterizedTest(name = "CA5 - {0} ex-coureur peut retirer sa photo")
    @EnumSource(value = Role.class, names = {"BENEVOLE", "ADMIN"})
    @DisplayName("CA5 - un BENEVOLE ou un ADMIN ex-coureur retire sa photo (RG1)")
    void doit_permettre_le_retrait_a_un_ex_coureur(Role role) {
        Compte exCoureur = avecPhoto(role);

        retirer.executer(exCoureur.id());

        assertThat(depot.comptes.get(exCoureur.id()).photo()).isNull();
        assertThat(stockage.fichiers).isEmpty();
    }

    @Test
    @DisplayName("CA5 - l'ADMIN_MASTER sans photo retire sans erreur")
    void doit_accepter_le_retrait_de_l_admin_master_sans_photo() {
        Compte master = compte(depot, "Patron", Role.ADMIN_MASTER, null);

        assertThatCode(() -> retirer.executer(master.id())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA5 - la photo d'un autre coureur n'est jamais touchée")
    void doit_isoler_les_photos_des_coureurs() {
        Compte autre = compte(depot, "Sophie", Role.COUREUR,
                new ReferencePhoto("ffffffffffffffffffffffffffffffff", FormatImage.PNG));
        stockage.poserFichier(autre.photo(), png());
        Compte coureur = avecPhoto(Role.COUREUR);

        retirer.executer(coureur.id());

        assertThat(depot.comptes.get(autre.id()).photo()).isEqualTo(autre.photo());
        assertThat(stockage.fichiers).containsOnlyKeys("ffffffffffffffffffffffffffffffff.png");
    }

    @Test
    @DisplayName("CA5 - un échec d'effacement du fichier ne remonte pas : la base fait foi, le retrait réussit")
    void doit_reussir_meme_si_l_effacement_du_fichier_echoue() {
        Compte coureur = avecPhoto(Role.COUREUR);
        stockage.panneEffacement = new IllegalStateException("droits insuffisants");

        assertThatCode(() -> retirer.executer(coureur.id())).doesNotThrowAnyException();

        assertThat(depot.comptes.get(coureur.id()).photo()).isNull();
    }
}
