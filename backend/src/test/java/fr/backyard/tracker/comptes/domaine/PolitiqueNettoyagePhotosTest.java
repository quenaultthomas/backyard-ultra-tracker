package fr.backyard.tracker.comptes.domaine;

import static org.assertj.core.api.Assertions.assertThat;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, RG4 et RG7 (CA7, CA9) : sélection des fichiers orphelins par le domaine.
 * <p>Types et signatures attendus, dans {@code fr.backyard.tracker.comptes.domaine} (Java pur) :
 * <ul>
 * <li>{@code record FichierPhoto(String nom, Instant modifieLe, boolean ordinaire)} : entrée directe du répertoire ;
 * {@code ordinaire} faux pour un sous-répertoire ou un lien symbolique ;</li>
 * <li>{@code PolitiqueNettoyagePhotos(Clock horloge)}, constantes publiques {@code SEUIL_ANCIENNETE = Duration.ofHours(1)}
 * et {@code SEUIL_GARDE_FOU = 10} ;</li>
 * <li>{@code Set<String> jetonsConformes(Collection<FichierPhoto>)} : jetons des fichiers de photo conformes (réguliers),
 * à passer en une seule fois à la base ;</li>
 * <li>{@code PlanNettoyage planifier(Collection<FichierPhoto> fichiers, Map<String, FormatImage> jetonsPortes)} où
 * {@code jetonsPortes} associe à chaque jeton porté par un Compte le format de sa photo ;</li>
 * <li>{@code record PlanNettoyage(List<String> orphelinsASupprimer, List<String> temporairesASupprimer, int ignores,
 * boolean suspendu, int nombreOrphelins, int nombreConformes)} (noms de fichiers) ; si {@code suspendu},
 * {@code orphelinsASupprimer} est vide, {@code nombreOrphelins} compte les orphelins anciens.</li>
 * </ul>
 */
class PolitiqueNettoyagePhotosTest {

    private static final Instant MAINTENANT = Instant.parse("2026-11-09T12:00:00Z");
    private final PolitiqueNettoyagePhotos politique = new PolitiqueNettoyagePhotos(
            Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    private static String jeton(int n) {
        return String.format("%032x", n);
    }

    private static FichierPhoto fichier(String nom, Duration age) {
        return new FichierPhoto(nom, MAINTENANT.minus(age), true);
    }

    private static Duration heures(int h) {
        return Duration.ofHours(h);
    }

    @Test
    @DisplayName("CA7 - seuls l'orphelin ancien et le .tmp ancien sont supprimés ; tout le reste est conservé")
    void doit_supprimer_exactement_l_orphelin_ancien_et_le_temporaire_ancien() {
        List<FichierPhoto> fichiers = List.of(
                fichier(jeton(1) + ".jpg", heures(5)),                    // A : porté
                fichier(jeton(2) + ".png", heures(5)),                    // B : orphelin ancien
                fichier(jeton(3) + ".webp", Duration.ofMinutes(59)),      // C : orphelin récent
                fichier(jeton(4) + ".jpg", Duration.ofMinutes(60)),       // D : exactement 60 min
                fichier(jeton(5) + ".jpg.tmp", heures(2)),                // E : tmp ancien
                fichier(jeton(6) + ".png.tmp", Duration.ofMinutes(5)),    // F : tmp récent
                fichier("lisez-moi.txt", heures(5)),
                fichier(jeton(0xabc).toUpperCase() + ".png", heures(5)),
                fichier("0".repeat(31) + ".jpg", heures(5)),
                fichier(jeton(8) + ".gif", heures(5)),
                new FichierPhoto("lost+found", MAINTENANT.minus(heures(5)), false));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of(jeton(1), FormatImage.JPEG));

        assertThat(plan.orphelinsASupprimer()).containsExactly(jeton(2) + ".png");
        assertThat(plan.temporairesASupprimer()).containsExactly(jeton(5) + ".jpg.tmp");
        assertThat(plan.suspendu()).isFalse();
    }

    @Test
    @DisplayName("CA7 - un orphelin modifié il y a 60 min exactement est conservé, à 61 min il est supprimé")
    void doit_supprimer_strictement_au_dela_d_une_heure() {
        List<FichierPhoto> fichiers = List.of(
                fichier(jeton(1) + ".jpg", Duration.ofMinutes(60)),
                fichier(jeton(2) + ".jpg", Duration.ofMinutes(61)),
                fichier(jeton(3) + ".jpg", Duration.ofMinutes(59)));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of());

        assertThat(plan.orphelinsASupprimer()).containsExactly(jeton(2) + ".jpg");
    }

    @Test
    @DisplayName("CA7 - un .tmp est supprimé au-delà d'une heure même si son jeton est porté, conservé à 60 min exactement")
    void doit_supprimer_un_temporaire_ancien_quel_que_soit_l_etat_de_la_base() {
        List<FichierPhoto> fichiers = List.of(
                fichier(jeton(1) + ".png.tmp", Duration.ofMinutes(61)),
                fichier(jeton(2) + ".webp.tmp", Duration.ofMinutes(60)));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of(jeton(1), FormatImage.PNG, jeton(2), FormatImage.WEBP));

        assertThat(plan.temporairesASupprimer()).containsExactly(jeton(1) + ".png.tmp");
        assertThat(plan.orphelinsASupprimer()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - un fichier dont le jeton est porté avec un autre format est conservé et compté parmi les ignorés")
    void doit_conserver_un_fichier_porte_dont_l_extension_differe_du_format() {
        List<FichierPhoto> fichiers = List.of(fichier(jeton(1) + ".png", heures(5)));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of(jeton(1), FormatImage.JPEG));

        assertThat(plan.orphelinsASupprimer()).isEmpty();
        assertThat(plan.ignores()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA7 - un nom non conforme est ignoré, jamais supprimé, et compté")
    void doit_ignorer_les_noms_non_conformes() {
        List<FichierPhoto> fichiers = List.of(
                fichier(".gitkeep", heures(5)),
                fichier("notes.txt", heures(5)),
                fichier(jeton(1) + ".jpg.tmp.tmp", heures(5)),
                fichier("a".repeat(33) + ".jpg", heures(5)));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of());

        assertThat(plan.orphelinsASupprimer()).isEmpty();
        assertThat(plan.temporairesASupprimer()).isEmpty();
        assertThat(plan.ignores()).isEqualTo(4);
    }

    @Test
    @DisplayName("CA7 - un répertoire ou un lien portant un nom de photo n'est jamais supprimé")
    void doit_ignorer_une_entree_non_ordinaire_meme_au_nom_conforme() {
        List<FichierPhoto> fichiers = List.of(new FichierPhoto(jeton(1) + ".jpg", MAINTENANT.minus(heures(5)), false));

        PlanNettoyage plan = politique.planifier(fichiers, Map.of());

        assertThat(plan.orphelinsASupprimer()).isEmpty();
        assertThat(plan.temporairesASupprimer()).isEmpty();
    }

    @Test
    @DisplayName("CA7 - la base est interrogée avec l'ensemble des jetons conformes, tmp et noms non conformes exclus")
    void doit_extraire_les_jetons_des_seuls_fichiers_conformes() {
        List<FichierPhoto> fichiers = List.of(
                fichier(jeton(1) + ".jpg", heures(5)),
                fichier(jeton(2) + ".png", heures(5)),
                fichier(jeton(3) + ".webp", Duration.ofMinutes(1)),
                fichier(jeton(4) + ".png.tmp", heures(5)),
                fichier("lisez-moi.txt", heures(5)),
                fichier(jeton(5) + ".gif", heures(5)),
                new FichierPhoto(jeton(6) + ".jpg", MAINTENANT, false));

        Set<String> jetons = politique.jetonsConformes(fichiers);

        assertThat(jetons).containsExactlyInAnyOrder(jeton(1), jeton(2), jeton(3));
    }

    // ---------- CA9 : garde-fou ----------

    /** {@code orphelins} fichiers anciens non portés, le reste porté ; retourne le plan. */
    private PlanNettoyage planAvec(int conformes, int orphelins) {
        List<FichierPhoto> fichiers = new ArrayList<>();
        Map<String, FormatImage> portes = new java.util.HashMap<>();
        for (int i = 0; i < conformes; i++) {
            fichiers.add(fichier(jeton(i + 1) + ".jpg", heures(5)));
            if (i >= orphelins) {
                portes.put(jeton(i + 1), FormatImage.JPEG);
            }
        }
        return politique.planifier(fichiers, portes);
    }

    @Test
    @DisplayName("CA9 - 40 fichiers conformes dont 21 orphelins anciens : suspendu, rien supprimé, nombres 21 et 40")
    void doit_suspendre_quand_les_orphelins_depassent_dix_et_la_moitie() {
        PlanNettoyage plan = planAvec(40, 21);

        assertThat(plan.suspendu()).isTrue();
        assertThat(plan.orphelinsASupprimer()).isEmpty();
        assertThat(plan.nombreOrphelins()).isEqualTo(21);
        assertThat(plan.nombreConformes()).isEqualTo(40);
    }

    @Test
    @DisplayName("CA9 - 40 fichiers conformes dont 20 orphelins (exactement la moitié) : les 20 sont supprimés")
    void doit_supprimer_quand_les_orphelins_sont_exactement_la_moitie() {
        PlanNettoyage plan = planAvec(40, 20);

        assertThat(plan.suspendu()).isFalse();
        assertThat(plan.orphelinsASupprimer()).hasSize(20);
    }

    @Test
    @DisplayName("CA9 - 40 fichiers conformes dont 11 orphelins (plus de 10 mais pas la moitié) : les 11 sont supprimés")
    void doit_supprimer_onze_orphelins_sur_quarante() {
        PlanNettoyage plan = planAvec(40, 11);

        assertThat(plan.suspendu()).isFalse();
        assertThat(plan.orphelinsASupprimer()).hasSize(11);
    }

    @Test
    @DisplayName("CA9 - 30 fichiers conformes dont 10 orphelins (10 n'est pas supérieur à 10) : les 10 sont supprimés")
    void doit_supprimer_dix_orphelins_sur_trente() {
        PlanNettoyage plan = planAvec(30, 10);

        assertThat(plan.suspendu()).isFalse();
        assertThat(plan.orphelinsASupprimer()).hasSize(10);
    }

    @Test
    @DisplayName("CA9 - 12 fichiers conformes dont 11 orphelins (plus de 10 et plus de la moitié) : suspendu")
    void doit_suspendre_onze_orphelins_sur_douze() {
        PlanNettoyage plan = planAvec(12, 11);

        assertThat(plan.suspendu()).isTrue();
        assertThat(plan.orphelinsASupprimer()).isEmpty();
    }

    @Test
    @DisplayName("CA9 - garde-fou déclenché : les 3 .tmp anciens sont tout de même supprimés")
    void doit_supprimer_les_temporaires_meme_quand_le_garde_fou_est_declenche() {
        List<FichierPhoto> fichiers = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            fichiers.add(fichier(jeton(i + 1) + ".jpg", heures(5)));
        }
        for (int i = 0; i < 3; i++) {
            fichiers.add(fichier(jeton(100 + i) + ".png.tmp", heures(5)));
        }

        PlanNettoyage plan = politique.planifier(fichiers, Map.of());

        assertThat(plan.suspendu()).isTrue();
        assertThat(plan.orphelinsASupprimer()).isEmpty();
        assertThat(plan.temporairesASupprimer()).hasSize(3);
    }

    @Test
    @DisplayName("CA9 - les orphelins récents ne comptent pas dans le garde-fou mais comptent parmi les fichiers conformes")
    void doit_compter_les_recents_parmi_les_conformes_sans_les_compter_comme_a_supprimer() {
        List<FichierPhoto> fichiers = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            fichiers.add(fichier(jeton(i + 1) + ".jpg", heures(5)));       // 11 anciens orphelins
        }
        for (int i = 0; i < 20; i++) {
            fichiers.add(fichier(jeton(100 + i) + ".jpg", Duration.ofMinutes(5))); // 20 récents (non à supprimer)
        }

        PlanNettoyage plan = politique.planifier(fichiers, Map.of());

        assertThat(plan.nombreConformes()).isEqualTo(31);
        assertThat(plan.suspendu()).isFalse();
        assertThat(plan.orphelinsASupprimer()).hasSize(11);
    }
}
