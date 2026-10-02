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
import fr.backyard.tracker.comptes.domaine.TentativesConnexion;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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

    private static final int OCTETS_VALEUR_FACTICE = 24;
    private static final Logger JOURNAL = System.getLogger(Connecter.class.getName());

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final RegistreTentativesConnexion registreTentatives;
    private final PolitiqueBlocage politiqueBlocage;
    private final Clock horloge;
    /** Vérifiée à la place d'une empreinte absente, pour un coût de calcul égal ; sa valeur source est oubliée. */
    private final String empreinteFactice;

    public Connecter(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse,
                     RegistreTentativesConnexion registreTentatives, PolitiqueBlocage politiqueBlocage,
                     Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.registreTentatives = registreTentatives;
        this.politiqueBlocage = politiqueBlocage;
        this.horloge = horloge;
        this.empreinteFactice = encodeurMotDePasse.encoder(new MotDePasse(valeurAleatoire()));
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
        verifierNonBloquee(cle, maintenant);
        Optional<Compte> compte = authentifier(cle, saisieMotDePasse);
        if (compte.isEmpty()) {
            enregistrerEchec(cle, maintenant);
            throw new IdentifiantsInvalidesException();
        }
        registreTentatives.effacer(cle);
        return compte.get();
    }

    private void verifierNonBloquee(String cle, Instant maintenant) {
        TentativesConnexion tentatives = registreTentatives.constater(cle, maintenant);
        if (tentatives.estBloquee(maintenant)) {
            JOURNAL.log(Level.INFO, "Connexion refusée : blocage en cours");
            throw new ConnexionBloqueeException(tentatives.tempsRestant(maintenant));
        }
    }

    private Optional<Compte> authentifier(String cle, String saisieMotDePasse) {
        Optional<Compte> compte = depotComptes.trouverParPseudoNormalise(cle).filter(Compte::peutSeConnecter);
        String empreinte = compte.map(Compte::empreinteMotDePasse).orElse(empreinteFactice);
        boolean motDePasseCorrect = encodeurMotDePasse.verifier(saisieMotDePasse, empreinte);
        return compte.filter(trouve -> motDePasseCorrect);
    }

    private void enregistrerEchec(String cle, Instant maintenant) {
        TentativesConnexion apres = registreTentatives.enregistrerEchec(cle, maintenant, politiqueBlocage);
        if (apres.blocageDeclencheA(maintenant)) {
            JOURNAL.log(Level.WARNING, "Connexion bloquée temporairement");
        }
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

    private static String valeurAleatoire() {
        byte[] octets = new byte[OCTETS_VALEUR_FACTICE];
        new SecureRandom().nextBytes(octets);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(octets);
    }
}
