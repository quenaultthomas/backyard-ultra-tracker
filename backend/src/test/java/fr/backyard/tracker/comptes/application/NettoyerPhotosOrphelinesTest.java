package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.FichierPhoto;
import fr.backyard.tracker.comptes.domaine.JetonsPhotosPortes;
import fr.backyard.tracker.comptes.domaine.RepertoirePhotos;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Correctif audit jalon 10, RG4 à RG7 (CA7, CA8, CA9, avec RG5e du CA10) : cas d'usage de nettoyage.
 * <p>Types et signatures attendus :
 * <ul>
 * <li>{@code comptes.domaine.RepertoirePhotos} (port) : {@code Optional<List<FichierPhoto>> lister()} (vide si le
 * répertoire est absent ; lève {@code UncheckedIOException} s'il est illisible) et {@code void supprimer(String nom)}
 * (lève {@code UncheckedIOException} en cas d'échec) ; il ne crée jamais le répertoire ;</li>
 * <li>{@code comptes.domaine.JetonsPhotosPortes} (port) : {@code Map<String, FormatImage> portesParmi(Collection<String>
 * jetons)}, jeton -> format de la photo du Compte qui le porte ; appelé une seule fois par balayage ;</li>
 * <li>{@code comptes.application.NettoyerPhotosOrphelines(RepertoirePhotos, JetonsPhotosPortes, Clock)} avec
 * {@code Bilan executer()} ; {@code record Bilan(int orphelinsSupprimes, int temporairesSupprimes, int ignores,
 * int erreurs)} ; ne lève jamais d'exception ;</li>
 * <li>journal (System.Logger) : INFO « Nettoyage des photos : N fichier(s) orphelin(s) supprimé(s), M fichier(s)
 * temporaire(s) supprimé(s), K fichier(s) ignoré(s) » ; WARN « Nettoyage des photos : E erreur(s) » si E > 0 ; WARN
 * « Nettoyage des photos suspendu : trop de fichiers orphelins (N sur T) » ; WARN unique commençant par « Nettoyage des
 * photos » pour une erreur de listage ou de base.</li>
 * </ul>
 */
class NettoyerPhotosOrphelinesTest {

    private static final Instant MAINTENANT = Instant.parse("2026-11-09T12:00:00Z");

    private final JournalApplicatif journal = JournalApplicatif.demarrerEnDebug();
    private final RepertoireEnMemoire repertoire = new RepertoireEnMemoire();
    private final PortesEnMemoire portes = new PortesEnMemoire();
    private final NettoyerPhotosOrphelines nettoyage = new NettoyerPhotosOrphelines(repertoire, portes,
            Clock.fixed(MAINTENANT, ZoneOffset.UTC));

    @AfterEach
    void fermer() {
        journal.fermer();
    }

    private static String jeton(int n) {
        return String.format("%032x", n);
    }

    private void poserAncien(String nom) {
        repertoire.fichiers.add(new FichierPhoto(nom, MAINTENANT.minus(Duration.ofHours(5)), true));
    }

    private List<String> messages(Level niveau) {
        return journal.evenementsApplicatifs().stream().filter(e -> e.getLevel() == niveau)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("CA7 - la base est interrogée une seule fois avec l'ensemble des jetons conformes, et seuls les orphelins sont supprimés")
    void doit_interroger_la_base_une_seule_fois_et_supprimer_les_orphelins() {
        poserAncien(jeton(1) + ".jpg");   // porté
        poserAncien(jeton(2) + ".png");   // orphelin
        poserAncien(jeton(3) + ".webp");  // orphelin
        portes.formats.put(jeton(1), FormatImage.JPEG);

        var bilan = nettoyage.executer();

        assertThat(portes.appels).hasSize(1);
        assertThat(portes.appels.getFirst()).containsExactlyInAnyOrder(jeton(1), jeton(2), jeton(3));
        assertThat(repertoire.supprimes).containsExactlyInAnyOrder(jeton(2) + ".png", jeton(3) + ".webp");
        assertThat(bilan.orphelinsSupprimes()).isEqualTo(2);
        assertThat(bilan.temporairesSupprimes()).isZero();
        assertThat(bilan.erreurs()).isZero();
    }

    @Test
    @DisplayName("CA7 - un .tmp ancien et des noms inconnus : le .tmp est supprimé, les autres sont comptés ignorés")
    void doit_supprimer_le_temporaire_et_compter_les_ignores() {
        poserAncien(jeton(1) + ".png.tmp");
        poserAncien("lisez-moi.txt");
        poserAncien(jeton(2) + ".gif");

        var bilan = nettoyage.executer();

        assertThat(repertoire.supprimes).containsExactly(jeton(1) + ".png.tmp");
        assertThat(bilan.temporairesSupprimes()).isEqualTo(1);
        assertThat(bilan.ignores()).isEqualTo(2);
    }

    // ---------- CA8 ----------

    @Test
    @DisplayName("CA8 - la base lève une exception : aucun fichier supprimé, un WARN, aucune exception ne sort")
    void doit_ne_rien_supprimer_quand_la_base_est_inaccessible() {
        poserAncien(jeton(1) + ".jpg");
        portes.panne = new IllegalStateException("base indisponible");

        assertThatCode(nettoyage::executer).doesNotThrowAnyException();

        assertThat(repertoire.supprimes).isEmpty();
        assertThat(messages(Level.WARN)).hasSize(1).allMatch(m -> m.startsWith("Nettoyage des photos"));
    }

    @Test
    @DisplayName("CA8 - le listage du répertoire lève une erreur d'entrée-sortie : aucun fichier supprimé, un WARN, aucune exception")
    void doit_ne_rien_supprimer_quand_le_repertoire_est_illisible() {
        repertoire.panneListage = new UncheckedIOException(new java.io.IOException("droits"));

        assertThatCode(nettoyage::executer).doesNotThrowAnyException();

        assertThat(repertoire.supprimes).isEmpty();
        assertThat(messages(Level.WARN)).hasSize(1).allMatch(m -> m.startsWith("Nettoyage des photos"));
    }

    @Test
    @DisplayName("CA8 - la suppression d'un orphelin sur 3 échoue : les 2 autres sont supprimés, bilan 2 supprimés et 1 erreur")
    void doit_continuer_apres_un_echec_de_suppression() {
        poserAncien(jeton(1) + ".jpg");
        poserAncien(jeton(2) + ".jpg");
        poserAncien(jeton(3) + ".jpg");
        // 3 orphelins sur 3 conformes : sous le seuil du garde-fou (pas plus de 10)
        repertoire.echecsSuppression.add(jeton(2) + ".jpg");

        var bilan = nettoyage.executer();

        assertThat(repertoire.supprimes).containsExactlyInAnyOrder(jeton(1) + ".jpg", jeton(3) + ".jpg");
        assertThat(bilan.orphelinsSupprimes()).isEqualTo(2);
        assertThat(bilan.erreurs()).isEqualTo(1);
        assertThat(messages(Level.WARN)).containsExactly("Nettoyage des photos : 1 erreur(s)");
    }

    @Test
    @DisplayName("CA8 - un fichier disparu entre le listage et la suppression est compté comme erreur sans exception")
    void doit_compter_comme_erreur_un_fichier_disparu() {
        poserAncien(jeton(1) + ".jpg");
        repertoire.echecsSuppression.add(jeton(1) + ".jpg");

        assertThatCode(nettoyage::executer).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("CA8 - répertoire absent : bilan 0, 0, 0, aucun WARN, la base n'est pas interrogée")
    void doit_rendre_un_bilan_vide_sans_avertissement_quand_le_repertoire_est_absent() {
        repertoire.absent = true;

        var bilan = nettoyage.executer();

        assertThat(bilan.orphelinsSupprimes()).isZero();
        assertThat(bilan.temporairesSupprimes()).isZero();
        assertThat(bilan.ignores()).isZero();
        assertThat(messages(Level.WARN)).isEmpty();
        assertThat(repertoire.supprimes).isEmpty();
        assertThat(portes.appels).isEmpty();
    }

    @Test
    @DisplayName("CA8 - répertoire vide : bilan 0, 0, 0 et aucun WARN")
    void doit_rendre_un_bilan_vide_pour_un_repertoire_vide() {
        var bilan = nettoyage.executer();

        assertThat(bilan.orphelinsSupprimes() + bilan.temporairesSupprimes() + bilan.ignores()).isZero();
        assertThat(messages(Level.WARN)).isEmpty();
    }

    // ---------- CA9 ----------

    @Test
    @DisplayName("CA9 - 21 orphelins sur 40 fichiers conformes : rien n'est supprimé, WARN « suspendu » avec les nombres 21 et 40")
    void doit_suspendre_le_nettoyage_et_journaliser_les_nombres() {
        for (int i = 1; i <= 40; i++) {
            poserAncien(jeton(i) + ".jpg");
            if (i > 21) {
                portes.formats.put(jeton(i), FormatImage.JPEG);
            }
        }

        var bilan = nettoyage.executer();

        assertThat(repertoire.supprimes).isEmpty();
        assertThat(bilan.orphelinsSupprimes()).isZero();
        assertThat(messages(Level.WARN))
                .containsExactly("Nettoyage des photos suspendu : trop de fichiers orphelins (21 sur 40)");
    }

    @Test
    @DisplayName("CA9 - garde-fou déclenché : les .tmp anciens sont tout de même supprimés")
    void doit_supprimer_les_temporaires_malgre_la_suspension() {
        for (int i = 1; i <= 12; i++) {
            poserAncien(jeton(i) + ".jpg");
        }
        poserAncien(jeton(100) + ".png.tmp");
        poserAncien(jeton(101) + ".png.tmp");
        poserAncien(jeton(102) + ".png.tmp");

        var bilan = nettoyage.executer();

        assertThat(bilan.temporairesSupprimes()).isEqualTo(3);
        assertThat(repertoire.supprimes).hasSize(3).allMatch(nom -> nom.endsWith(".tmp"));
    }

    // ---------- CA10 (RG5e, niveau unitaire) ----------

    @Test
    @DisplayName("CA10 - ligne INFO de bilan avec les seuls nombres, sans nom de fichier, jeton ni chemin")
    void doit_journaliser_un_bilan_sans_donnee_de_fichier() {
        poserAncien(jeton(1) + ".jpg");
        poserAncien(jeton(2) + ".jpg");
        poserAncien(jeton(3) + ".jpg");
        poserAncien(jeton(4) + ".jpg.tmp");
        poserAncien("lisez-moi.txt");
        poserAncien("autre.txt");

        nettoyage.executer();

        assertThat(messages(Level.INFO)).containsExactly("Nettoyage des photos : 3 fichier(s) orphelin(s) supprimé(s), "
                + "1 fichier(s) temporaire(s) supprimé(s), 2 fichier(s) ignoré(s)");
        for (String texte : journal.messagesEtExceptions()) {
            assertThat(texte).doesNotContain(jeton(1), jeton(4), "lisez-moi", "autre.txt", ".jpg", ".tmp");
        }
    }

    // ---------- doubles ----------

    private static final class RepertoireEnMemoire implements RepertoirePhotos {
        final List<FichierPhoto> fichiers = new ArrayList<>();
        final List<String> supprimes = new ArrayList<>();
        final Set<String> echecsSuppression = new HashSet<>();
        boolean absent;
        RuntimeException panneListage;

        @Override
        public Optional<List<FichierPhoto>> lister() {
            if (panneListage != null) {
                throw panneListage;
            }
            return absent ? Optional.empty() : Optional.of(List.copyOf(fichiers));
        }

        @Override
        public void supprimer(String nom) {
            if (echecsSuppression.contains(nom)) {
                throw new UncheckedIOException(new java.io.IOException("suppression impossible"));
            }
            supprimes.add(nom);
        }
    }

    private static final class PortesEnMemoire implements JetonsPhotosPortes {
        final Map<String, FormatImage> formats = new HashMap<>();
        final List<Collection<String>> appels = new ArrayList<>();
        RuntimeException panne;

        @Override
        public Map<String, FormatImage> portesParmi(Collection<String> jetons) {
            appels.add(List.copyOf(jetons));
            if (panne != null) {
                throw panne;
            }
            Map<String, FormatImage> trouves = new HashMap<>();
            jetons.stream().filter(formats::containsKey).forEach(j -> trouves.put(j, formats.get(j)));
            return trouves;
        }
    }
}
