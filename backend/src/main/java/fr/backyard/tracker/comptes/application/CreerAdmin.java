package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Création d'un compte ADMIN par l'admin master (le rôle de l'appelant est contrôlé avant ce cas d'usage). */
@Service
public class CreerAdmin {

    /** Saisie de création : le mot de passe n'apparaît jamais dans {@link #toString()}. */
    public record Commande(String pseudo, String motDePasse) {

        @Override
        public String toString() {
            return "Commande[pseudo=" + pseudo + ", motDePasse=masqué]";
        }
    }

    private final CreationCompte creationCompte;

    public CreerAdmin(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge) {
        this.creationCompte = new CreationCompte(depotComptes, encodeurMotDePasse, horloge);
    }

    /**
     * @throws DonneesCompteInvalidesException toutes les violations de saisie, avant toute vérification d'unicité
     * @throws PseudoDejaUtiliseException      si le pseudo est déjà pris, tous rôles confondus
     */
    @Transactional
    public Compte executer(Commande commande) {
        return creationCompte.creer(commande.pseudo(), commande.motDePasse(), Compte::creerAdmin);
    }
}
