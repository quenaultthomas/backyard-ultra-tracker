package fr.backyard.tracker.comptes.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import fr.backyard.tracker.JournalApplicatif;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.infrastructure.RegistreEchecsParAdresseEnMemoire;
import fr.backyard.tracker.comptes.infrastructure.RegistreTentativesConnexionEnMemoire;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Incrément 8.5, CA7 (non-régression) : les messages de journal du plafond de connexion de 8.4 sont inchangés (RG8). */
class ConnecterJournalPlafondAdresseTest {

    private static final Instant T = Instant.parse("2026-10-26T10:00:00Z");

    private final DepotComptes depotVide = new DepotComptes() {
        @Override
        public boolean existeParPseudoNormalise(String pseudoNormalise) {
            return false;
        }

        @Override
        public void enregistrer(fr.backyard.tracker.comptes.domaine.Compte compte) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<fr.backyard.tracker.comptes.domaine.Compte> trouverParPseudoNormalise(String pseudo) {
            return Optional.empty();
        }
    };
    private final EncodeurMotDePasse encodeur = new EncodeurMotDePasse() {
        @Override
        public String encoder(fr.backyard.tracker.comptes.domaine.MotDePasse motDePasse) {
            return "empreinte";
        }

        @Override
        public boolean verifier(String motDePasseEnClair, String empreinte) {
            return false;
        }
    };
    private final Connecter connecter = new Connecter(depotVide, encodeur, new RegistreTentativesConnexionEnMemoire(),
            PolitiqueBlocage.parDefaut(), new RegistreEchecsParAdresseEnMemoire(), new PlafondEchecsParAdresse(3, 60),
            () -> Optional.of("203.0.113.1"), Clock.fixed(T, ZoneOffset.UTC));
    private JournalApplicatif journal;

    @AfterEach
    void fermerLeJournal() {
        if (journal != null) {
            journal.fermer();
        }
    }

    @Test
    @DisplayName("CA7 - les lignes de la connexion restent « Connexion : plafond d'échecs par adresse atteint » (WARN) et « Connexion refusée : ... » (DEBUG)")
    void doit_conserver_le_texte_des_journaux_de_la_connexion() {
        journal = JournalApplicatif.demarrerEnDebug();

        for (int i = 0; i < 3; i++) {
            int numero = i;
            assertThatThrownBy(() -> connecter.executer("Inconnu" + numero, "mauvais-mot-de-passe-1"))
                    .isInstanceOf(IdentifiantsInvalidesException.class);
        }
        assertThatThrownBy(() -> connecter.executer("Inconnu9", "mauvais-mot-de-passe-1"))
                .isInstanceOf(ConnexionBloqueeException.class);

        assertThat(messages(Level.WARN)).containsExactly("Connexion : plafond d'échecs par adresse atteint");
        assertThat(messages(Level.DEBUG)).containsExactly("Connexion refusée : plafond d'échecs par adresse atteint");
    }

    private List<String> messages(Level niveau) {
        return journal.evenementsApplicatifs().stream()
                .filter(e -> e.getLevel() == niveau)
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith("Connexion"))
                .toList();
    }
}
