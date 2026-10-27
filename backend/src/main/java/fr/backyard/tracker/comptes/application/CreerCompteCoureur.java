package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.AdmissionCreationCompte;
import fr.backyard.tracker.comptes.domaine.AdresseDeLaRequete;
import fr.backyard.tracker.comptes.domaine.AdresseSuivie;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CreationsCompteExcessivesException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import fr.backyard.tracker.comptes.domaine.PlafondCreationsCompteParAdresse;
import fr.backyard.tracker.comptes.domaine.PlafondEchecsParAdresse;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.RegistreCreationsCompteParAdresse;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import org.springframework.stereotype.Service;

/**
 * Création libre d'un compte coureur par un visiteur. Toute tentative émise depuis une adresse connue est d'abord
 * admise par le plafond de créations par adresse (SEC-15), quelle qu'en soit l'issue ; un refus précède toute
 * validation, lecture du dépôt et tout hachage. Journal sans pseudo, mot de passe ni adresse.
 */
@Service
public class CreerCompteCoureur {

    private static final Logger JOURNAL = System.getLogger(CreerCompteCoureur.class.getName());

    private final CreationCompte creationCompte;
    private final Clock horloge;
    private final AdresseDeLaRequete adresseDeLaRequete;
    private final RegistreCreationsCompteParAdresse registreCreations;
    private final PlafondEchecsParAdresse fenetreGlissante;

    public CreerCompteCoureur(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge,
                              AdresseDeLaRequete adresseDeLaRequete,
                              RegistreCreationsCompteParAdresse registreCreations,
                              PlafondCreationsCompteParAdresse plafond) {
        this.creationCompte = new CreationCompte(depotComptes, encodeurMotDePasse, horloge);
        this.horloge = horloge;
        this.adresseDeLaRequete = adresseDeLaRequete;
        this.registreCreations = registreCreations;
        this.fenetreGlissante = plafond.enFenetreGlissante();
    }

    /**
     * @throws CreationsCompteExcessivesException l'adresse a atteint son plafond de créations : rien n'est lu ni haché
     * @throws DonneesCompteInvalidesException    toutes les violations de saisie, avant toute vérification d'unicité
     * @throws PseudoDejaUtiliseException         si le pseudo est déjà pris, quelle qu'en soit la casse
     * @throws HachageSatureException             trop de calculs d'empreinte simultanés : aucun compte créé
     */
    public Compte executer(String saisiePseudo, String saisieMotDePasse) {
        admettreDepuisLAdresseCourante();
        return creationCompte.creer(saisiePseudo, saisieMotDePasse, Compte::creerCoureur);
    }

    /** Adresse inconnue (hors requête HTTP) : ni contrôle ni comptage. */
    private void admettreDepuisLAdresseCourante() {
        adresseDeLaRequete.courante().map(AdresseSuivie::depuis).ifPresent(adresse -> {
            AdmissionCreationCompte admission = registreCreations.admettre(adresse, horloge.instant(), fenetreGlissante);
            if (!admission.admise()) {
                JOURNAL.log(Level.DEBUG, "Création de compte refusée : plafond par adresse atteint");
                throw new CreationsCompteExcessivesException(admission.tempsRestant());
            }
            if (admission.plafondAtteint()) {
                JOURNAL.log(Level.WARNING, "Création de compte : plafond par adresse atteint");
            }
        });
    }
}
