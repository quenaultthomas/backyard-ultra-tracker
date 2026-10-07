package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AnnulationInscriptions;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.SuppressionCompteInterditeException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Suppression de son propre Compte coureur, confirmée par le mot de passe actuel (mêmes contrôles et même comptage
 * des échecs que le changement de mot de passe). Dans une seule transaction : annulation des Inscriptions aux Courses
 * non démarrées, anonymisation du Compte, remise à zéro du compteur de l'ancien pseudo, puis fermeture de toutes les
 * sessions du Compte en dernier. Journal sans pseudo ni mot de passe.
 */
@Service
public class SupprimerCompteCoureur {

    private static final Logger JOURNAL = System.getLogger(SupprimerCompteCoureur.class.getName());

    /** Saisie de la suppression : le mot de passe n'apparaît jamais dans {@link #toString()}. */
    public record Commande(UUID compteId, String motDePasseActuel) {

        @Override
        public String toString() {
            return "Commande[compteId=" + compteId + ", motDePasseActuel=masqué]";
        }
    }

    private final DepotComptes depotComptes;
    private final LimitationTentatives limitation;
    private final ConfirmationMotDePasse confirmation;
    private final AnnulationInscriptions annulationInscriptions;
    private final InvalidationAutresSessions invalidationSessions;
    private final Clock horloge;

    public SupprimerCompteCoureur(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse,
                                  RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage,
                                  AnnulationInscriptions annulationInscriptions,
                                  InvalidationAutresSessions invalidationSessions, Clock horloge) {
        this.depotComptes = depotComptes;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
        this.confirmation = new ConfirmationMotDePasse(depotComptes, encodeurMotDePasse, limitation, JOURNAL,
                "Échec de suppression de compte (compte {0})");
        this.annulationInscriptions = annulationInscriptions;
        this.invalidationSessions = invalidationSessions;
        this.horloge = horloge;
    }

    /**
     * @throws DonneesCompteInvalidesException          mot de passe absent ou vide
     * @throws CompteIntrouvableOuInutilisableException compte disparu ou déjà anonymisé : rien n'est modifié
     * @throws ConnexionBloqueeException                pendant un blocage du pseudo du compte
     * @throws MotDePasseActuelIncorrectException       mot de passe faux ou hors bornes : rien n'est modifié
     * @throws SuppressionCompteInterditeException      le Compte n'est pas un Compte coureur : rien n'est modifié
     */
    @Transactional
    public void executer(Commande commande) {
        verifierSaisie(commande.motDePasseActuel());
        Compte compte = confirmation.confirmer(commande.compteId(), commande.motDePasseActuel(), horloge.instant());
        Compte anonyme = compte.anonymiser();
        int annulees = annulationInscriptions.annulerPour(compte.id());
        depotComptes.mettreAJour(anonyme);
        limitation.oublier(compte.pseudoNormalise());
        invalidationSessions.invaliderToutesLesSessions(compte.id());
        JOURNAL.log(Level.INFO, "Compte supprimé (compte {0}, {1} inscription(s) annulée(s))", compte.id(),
                annulees);
    }

    private static void verifierSaisie(String motDePasseActuel) {
        MotDePasse.verifierActuel(motDePasseActuel).ifPresent(violation -> {
            throw new DonneesCompteInvalidesException(List.of(violation));
        });
    }
}
