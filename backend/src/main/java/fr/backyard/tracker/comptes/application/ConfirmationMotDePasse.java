package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Confirmation d'une action sur son propre Compte par le mot de passe actuel (changement de mot de passe,
 * suppression du Compte). Ordre : Compte utilisable, blocage, vérification ; l'échec est compté dans le registre de la
 * connexion, sur la même clé. Le Compte est lu sous verrou jusqu'à la fin de la transaction de l'appelant : deux
 * actions simultanées sur le même Compte sont sérialisées et la seconde voit l'état laissé par la première.
 * Journal sans pseudo ni mot de passe.
 */
final class ConfirmationMotDePasse {

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final LimitationTentatives limitation;
    private final Logger journal;
    private final String messageEchec;
    /** Vérifiée pour un compte introuvable ou inutilisable, pour un coût de calcul égal. */
    private final String empreinteFactice;

    /** @param messageEchec ligne de journal d'un mot de passe refusé, paramétrée par l'identifiant du compte */
    ConfirmationMotDePasse(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse,
                           LimitationTentatives limitation, Logger journal, String messageEchec) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.limitation = limitation;
        this.journal = journal;
        this.messageEchec = messageEchec;
        this.empreinteFactice = EmpreinteFactice.calculer(encodeurMotDePasse);
    }

    /**
     * @return le Compte confirmé, utilisable
     * @throws CompteIntrouvableOuInutilisableException compte disparu ou sans empreinte
     * @throws ConnexionBloqueeException                pendant un blocage du pseudo du compte
     * @throws MotDePasseActuelIncorrectException       mot de passe faux ou hors bornes
     */
    Compte confirmer(UUID compteId, String motDePasseActuel, Instant maintenant) {
        Compte compte = compteUtilisable(compteId, motDePasseActuel);
        limitation.verifierNonBloquee(compte.pseudoNormalise(), maintenant);
        verifierMotDePasseActuel(compte, motDePasseActuel, maintenant);
        return compte;
    }

    private Compte compteUtilisable(UUID compteId, String motDePasseActuel) {
        Optional<Compte> compte = depotComptes.trouverParIdPourModification(compteId).filter(Compte::peutSeConnecter);
        if (compte.isEmpty()) {
            verifierSansEffet(motDePasseActuel);
            throw new CompteIntrouvableOuInutilisableException();
        }
        return compte.get();
    }

    /** Même coût de calcul que pour un compte existant ; le résultat est sans objet. */
    private void verifierSansEffet(String motDePasseActuel) {
        if (!MotDePasse.excedeLongueurMax(motDePasseActuel)) {
            encodeurMotDePasse.verifier(motDePasseActuel, empreinteFactice);
        }
    }

    /** Une valeur hors bornes est refusée sans calcul d'empreinte ni comptage. */
    private void verifierMotDePasseActuel(Compte compte, String motDePasseActuel, Instant maintenant) {
        if (MotDePasse.excedeLongueurMax(motDePasseActuel)) {
            throw echec(compte);
        }
        if (!encodeurMotDePasse.verifier(motDePasseActuel, compte.empreinteMotDePasse())) {
            limitation.enregistrerEchec(compte.pseudoNormalise(), maintenant);
            throw echec(compte);
        }
    }

    private MotDePasseActuelIncorrectException echec(Compte compte) {
        journal.log(Level.INFO, messageEchec, compte.id());
        return new MotDePasseActuelIncorrectException();
    }
}
