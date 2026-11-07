package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.application.DoublesPhoto.compte;
import static fr.backyard.tracker.courses.OctetsDeLogo.ascii;
import static fr.backyard.tracker.courses.OctetsDeLogo.jpeg;
import static fr.backyard.tracker.courses.OctetsDeLogo.png;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.application.DoublesPhoto.AnnulationNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.DepotEnMemoire;
import fr.backyard.tracker.comptes.application.DoublesPhoto.EncodeurPrefixe;
import fr.backyard.tracker.comptes.application.DoublesPhoto.InvalidationNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.RegistreNeutre;
import fr.backyard.tracker.comptes.application.DoublesPhoto.StockageEnMemoire;
import fr.backyard.tracker.comptes.application.SupprimerCompteCoureur.Commande;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.PhotoIntrouvableException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.ReferencePhoto;
import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Journal de la photo (10.1a, CA6, RG14). Messages exacts attendus (le niveau est contrôlé) :
 * INFO « Photo de profil enregistrée (compte {id}) », INFO « Photo de profil retirée (compte {id}) »,
 * INFO « Photo de profil effacée (compte {id}) », WARN « Effacement de la photo impossible (compte {id}) »,
 * ERROR « Fichier de photo introuvable (compte {id}) ». Jamais de jeton, chemin, pseudo, octets.
 */
class JournalPhotoTest {

    private static final String JETON_ANCIEN = "0123456789abcdef0123456789abcdef";
    private static final ReferencePhoto ANCIENNE = new ReferencePhoto(JETON_ANCIEN, FormatImage.PNG);
    private static final String PSEUDO = "PseudoSecretDeKarim";

    private final JournalApplicatif journal = JournalApplicatif.demarrerEnDebug();
    private final List<String> ordre = new ArrayList<>();
    private final DepotEnMemoire depot = new DepotEnMemoire(ordre);
    private final StockageEnMemoire stockage = new StockageEnMemoire(ordre);
    private final EffacementPhoto effacement = new EffacementPhoto(stockage);
    private final EnregistrerPhoto enregistrer = new EnregistrerPhoto(depot, stockage, effacement);
    private final RetirerPhoto retirer = new RetirerPhoto(depot, effacement);
    private final LirePhoto lire = new LirePhoto(depot, stockage);

    @AfterEach
    void fermer() {
        journal.fermer();
    }

    private List<ILoggingEvent> evenements() {
        return journal.evenementsApplicatifs();
    }

    private List<String> messagesDe(Level niveau) {
        return evenements().stream().filter(e -> e.getLevel() == niveau).map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void assertJournalSansDonneeSensible(String... interdits) {
        List<String> tout = new ArrayList<>(journal.messagesEtExceptions());
        evenements().forEach(e -> tout.add(e.getLoggerName() + " " + e.getFormattedMessage()));
        for (String texte : tout) {
            assertThat(texte).doesNotContain(PSEUDO).doesNotContain(JETON_ANCIEN).doesNotContain("photo.jpg")
                    .doesNotContain("image/").doesNotContain("/donnees").doesNotContain("[B@");
            for (String interdit : interdits) {
                assertThat(texte).doesNotContain(interdit);
            }
        }
    }

    @Test
    @DisplayName("CA6 - un envoi réussi écrit une ligne INFO avec l'identifiant du Compte, sans jeton ni pseudo")
    void doit_journaliser_l_enregistrement_en_info() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, null);

        String jeton = enregistrer.executer(coureur.id(), jpeg());

        assertThat(messagesDe(Level.INFO)).containsExactly("Photo de profil enregistrée (compte " + coureur.id() + ")");
        assertJournalSansDonneeSensible(jeton);
    }

    @Test
    @DisplayName("CA6 - un retrait avec photo écrit une ligne INFO « retirée », sans WARN")
    void doit_journaliser_le_retrait_avec_photo() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, ANCIENNE);
        stockage.poserFichier(ANCIENNE, png());

        retirer.executer(coureur.id());

        assertThat(messagesDe(Level.INFO)).contains("Photo de profil retirée (compte " + coureur.id() + ")");
        assertThat(messagesDe(Level.WARN)).isEmpty();
        assertJournalSansDonneeSensible();
    }

    @Test
    @DisplayName("CA6 - un retrait sans photo n'écrit aucune ligne INFO ou supérieure")
    void doit_ne_rien_journaliser_pour_un_retrait_sans_photo() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, null);

        retirer.executer(coureur.id());

        assertThat(evenements()).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
    }

    @Test
    @DisplayName("CA6 - l'effacement lié à une suppression écrit INFO « effacée » avec l'identifiant du Compte")
    void doit_journaliser_l_effacement_en_info() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, ANCIENNE);
        stockage.poserFichier(ANCIENNE, png());

        effacement.effacerApresValidation(coureur);

        assertThat(messagesDe(Level.INFO)).containsExactly("Photo de profil effacée (compte " + coureur.id() + ")");
        assertJournalSansDonneeSensible();
    }

    @Test
    @DisplayName("CA6 - un échec d'effacement écrit un WARN sans jeton, chemin ni détail de l'exception")
    void doit_journaliser_l_echec_d_effacement_en_warn() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, ANCIENNE);
        stockage.panneEffacement = new IllegalStateException("/donnees/photos/" + JETON_ANCIEN + ".png illisible");

        effacement.effacerApresValidation(coureur);

        assertThat(messagesDe(Level.WARN)).containsExactly("Effacement de la photo impossible (compte " + coureur.id() + ")");
        assertThat(messagesDe(Level.INFO)).doesNotContain("Photo de profil effacée (compte " + coureur.id() + ")");
        assertJournalSansDonneeSensible();
    }

    @Test
    @DisplayName("CA6 - suppression d'un coureur avec photo : ligne « effacée », sans pseudo ni jeton")
    void doit_journaliser_l_effacement_lors_de_la_suppression_du_coureur() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, ANCIENNE);
        stockage.poserFichier(ANCIENNE, png());
        SupprimerCompteCoureur supprimer = new SupprimerCompteCoureur(depot, new EncodeurPrefixe(), new RegistreNeutre(),
                PolitiqueBlocage.parDefaut(), new AnnulationNeutre(), new InvalidationNeutre(),
                Clock.fixed(DoublesPhoto.T, ZoneOffset.UTC), effacement);

        supprimer.executer(new Commande(coureur.id(), "un-mot-de-passe-12"));

        assertThat(messagesDe(Level.INFO)).contains("Photo de profil effacée (compte " + coureur.id() + ")");
        assertJournalSansDonneeSensible();
    }

    @Test
    @DisplayName("CA6 - fichier manquant à la lecture : une ligne ERROR avec l'identifiant du Compte, sans jeton")
    void doit_journaliser_un_fichier_manquant_en_error() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, ANCIENNE);

        Throwable echec = catchThrowable(() -> lire.executer(JETON_ANCIEN));

        assertThat(echec).isInstanceOf(PhotoIntrouvableException.class);
        assertThat(messagesDe(Level.ERROR)).containsExactly("Fichier de photo introuvable (compte " + coureur.id() + ")");
        assertJournalSansDonneeSensible();
    }

    @Test
    @DisplayName("CA6 - refus (fichier invalide, rôle, jeton inconnu ou mal formé) : aucune ligne INFO ou supérieure")
    void doit_ne_rien_journaliser_pour_un_refus() {
        Compte coureur = compte(depot, PSEUDO, Role.COUREUR, null);
        Compte benevole = compte(depot, "Leo", Role.BENEVOLE, null);

        catchThrowable(() -> enregistrer.executer(coureur.id(), ascii("GIF89a" + "x".repeat(20))));
        catchThrowable(() -> enregistrer.executer(coureur.id(), new byte[0]));
        catchThrowable(() -> enregistrer.executer(benevole.id(), png()));
        catchThrowable(() -> lire.executer("00112233445566778899aabbccddeeff"));
        catchThrowable(() -> lire.executer("../../etc/passwd"));

        assertThat(evenements()).noneMatch(e -> e.getLevel().isGreaterOrEqual(Level.INFO));
    }
}
