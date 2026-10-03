package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.CompteIntrouvableOuInutilisableException;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.InvalidationAutresSessions;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasseActuelIncorrectException;
import fr.backyard.tracker.comptes.domaine.NouveauMotDePasseIdentiqueException;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Changement de son propre mot de passe, l'ancien étant exigé. Ordre des contrôles : format, blocage,
 * ancien mot de passe (échec compté dans le registre de la connexion, même clé), différence avec
 * l'ancien, puis remplacement de l'empreinte, remise à zéro du compteur et fermeture des autres sessions.
 * Journal sans pseudo ni mot de passe.
 */
@Service
public class ChangerMotDePasse {

    static final String CHAMP_NOUVEAU_MOT_DE_PASSE = "nouveauMotDePasse";
    private static final Logger JOURNAL = System.getLogger(ChangerMotDePasse.class.getName());

    /** Saisie du changement : les mots de passe n'apparaissent jamais dans {@link #toString()}. */
    public record Commande(UUID compteId, String motDePasseActuel, String nouveauMotDePasse) {

        @Override
        public String toString() {
            return "Commande[compteId=" + compteId + ", motDePasseActuel=masqué, nouveauMotDePasse=masqué]";
        }
    }

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final LimitationTentatives limitation;
    private final InvalidationAutresSessions invalidationAutresSessions;
    private final Clock horloge;
    /** Vérifiée pour un compte introuvable ou inutilisable, pour un coût de calcul égal. */
    private final String empreinteFactice;

    public ChangerMotDePasse(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse,
                             RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage,
                             InvalidationAutresSessions invalidationAutresSessions, Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
        this.invalidationAutresSessions = invalidationAutresSessions;
        this.horloge = horloge;
        this.empreinteFactice = EmpreinteFactice.calculer(encodeurMotDePasse);
    }

    /**
     * @throws DonneesCompteInvalidesException          toutes les violations de format, avant tout autre contrôle
     * @throws CompteIntrouvableOuInutilisableException compte disparu ou sans empreinte : rien n'est modifié
     * @throws ConnexionBloqueeException                pendant un blocage du pseudo du compte
     * @throws MotDePasseActuelIncorrectException       ancien mot de passe faux ou hors bornes
     * @throws NouveauMotDePasseIdentiqueException      nouveau mot de passe égal à l'ancien
     */
    @Transactional
    public void executer(Commande commande) {
        verifierSaisies(commande);
        Compte compte = compteUtilisable(commande);
        String cle = compte.pseudoNormalise();
        Instant maintenant = horloge.instant();
        limitation.verifierNonBloquee(cle, maintenant);
        verifierMotDePasseActuel(compte, commande.motDePasseActuel(), maintenant);
        MotDePasse nouveau = new MotDePasse(commande.nouveauMotDePasse());
        nouveau.exigerDifferentDe(commande.motDePasseActuel());
        depotComptes.mettreAJour(compte.changerMotDePasse(encodeurMotDePasse.encoder(nouveau)));
        limitation.oublier(cle);
        invalidationAutresSessions.invaliderAutresSessions(compte.id());
        JOURNAL.log(Level.INFO, "Mot de passe modifié (compte {0})", compte.id());
    }

    private Compte compteUtilisable(Commande commande) {
        Optional<Compte> compte = depotComptes.trouverParId(commande.compteId()).filter(Compte::peutSeConnecter);
        if (compte.isEmpty()) {
            verifierSansEffet(commande.motDePasseActuel());
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
            throw echecMotDePasseActuel(compte);
        }
        if (!encodeurMotDePasse.verifier(motDePasseActuel, compte.empreinteMotDePasse())) {
            limitation.enregistrerEchec(compte.pseudoNormalise(), maintenant);
            throw echecMotDePasseActuel(compte);
        }
    }

    private static MotDePasseActuelIncorrectException echecMotDePasseActuel(Compte compte) {
        JOURNAL.log(Level.INFO, "Échec de changement de mot de passe (compte {0})", compte.id());
        return new MotDePasseActuelIncorrectException();
    }

    private static void verifierSaisies(Commande commande) {
        List<ViolationValidation> violations = Stream.of(
                        MotDePasse.verifierActuel(commande.motDePasseActuel()),
                        MotDePasse.verifier(commande.nouveauMotDePasse())
                                .map(violation -> violation.pourChamp(CHAMP_NOUVEAU_MOT_DE_PASSE)))
                .flatMap(Optional::stream)
                .toList();
        if (!violations.isEmpty()) {
            throw new DonneesCompteInvalidesException(violations);
        }
    }
}
