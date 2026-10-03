package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConnexionBloqueeException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.PolitiqueBlocage;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.RegistreTentativesConnexion;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connexion d'un compte par pseudo et mot de passe. Tous les échecs d'identification sont
 * indiscernables : même exception et, hors saisie hors bornes, une vérification d'empreinte complète.
 * Les échecs dans les bornes sont comptés par pseudo normalisé ; pendant un blocage, la tentative est
 * refusée sans interroger le dépôt ni vérifier d'empreinte. Journal sans pseudo ni mot de passe.
 */
@Service
public class Connecter {

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final LimitationTentatives limitation;
    private final Clock horloge;
    /** Vérifiée à la place d'une empreinte absente, pour un coût de calcul égal. */
    private final String empreinteFactice;

    public Connecter(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse,
                     RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage,
                     Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.limitation = new LimitationTentatives(registreTentatives, politiqueBlocage);
        this.horloge = horloge;
        this.empreinteFactice = EmpreinteFactice.calculer(encodeurMotDePasse);
    }

    /**
     * @throws DonneesCompteInvalidesException pseudo et/ou mot de passe absents
     * @throws ConnexionBloqueeException      saisie dans les bornes pendant un blocage du pseudo
     * @throws IdentifiantsInvalidesException  pour tout autre échec, quelle qu'en soit la cause
     */
    @Transactional(readOnly = true)
    public Compte executer(String saisiePseudo, String saisieMotDePasse) {
        verifierPresence(saisiePseudo, saisieMotDePasse);
        if (Pseudo.excedeLongueurMax(saisiePseudo) || MotDePasse.excedeLongueurMax(saisieMotDePasse)) {
            throw new IdentifiantsInvalidesException();
        }
        String cle = Pseudo.normaliser(saisiePseudo);
        Instant maintenant = horloge.instant();
        limitation.verifierNonBloquee(cle, maintenant);
        Optional<Compte> compte = authentifier(cle, saisieMotDePasse);
        if (compte.isEmpty()) {
            limitation.enregistrerEchec(cle, maintenant);
            throw new IdentifiantsInvalidesException();
        }
        limitation.oublier(cle);
        return compte.get();
    }

    private Optional<Compte> authentifier(String cle, String saisieMotDePasse) {
        Optional<Compte> compte = depotComptes.trouverParPseudoNormalise(cle).filter(Compte::peutSeConnecter);
        String empreinte = compte.map(Compte::empreinteMotDePasse).orElse(empreinteFactice);
        boolean motDePasseCorrect = encodeurMotDePasse.verifier(saisieMotDePasse, empreinte);
        return compte.filter(trouve -> motDePasseCorrect);
    }

    private static void verifierPresence(String saisiePseudo, String saisieMotDePasse) {
        List<ViolationValidation> violations = Stream.of(
                        Pseudo.verifierPresence(saisiePseudo),
                        MotDePasse.verifierPresence(saisieMotDePasse))
                .flatMap(Optional::stream)
                .toList();
        if (!violations.isEmpty()) {
            throw new DonneesCompteInvalidesException(violations);
        }
    }
}
