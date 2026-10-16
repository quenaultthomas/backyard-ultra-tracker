package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.HachageSatureException;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** Création libre d'un compte coureur par un visiteur. */
@Service
public class CreerCompteCoureur {

    private final CreationCompte creationCompte;

    public CreerCompteCoureur(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge) {
        this.creationCompte = new CreationCompte(depotComptes, encodeurMotDePasse, horloge);
    }

    /**
     * @throws DonneesCompteInvalidesException toutes les violations de saisie, avant toute vérification d'unicité
     * @throws PseudoDejaUtiliseException      si le pseudo est déjà pris, quelle qu'en soit la casse
     * @throws HachageSatureException         trop de calculs d'empreinte simultanés : aucun compte créé
     */
    public Compte executer(String saisiePseudo, String saisieMotDePasse) {
        return creationCompte.creer(saisiePseudo, saisieMotDePasse, Compte::creerCoureur);
    }
}
