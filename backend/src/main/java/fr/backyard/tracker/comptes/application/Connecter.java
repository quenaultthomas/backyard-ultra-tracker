package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.IdentifiantsInvalidesException;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Connexion d'un compte par pseudo et mot de passe. Tous les échecs d'identification sont
 * indiscernables : même exception et, hors saisie hors bornes, une vérification d'empreinte complète.
 */
@Service
public class Connecter {

    private static final int OCTETS_VALEUR_FACTICE = 24;

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    /** Vérifiée à la place d'une empreinte absente, pour un coût de calcul égal ; sa valeur source est oubliée. */
    private final String empreinteFactice;

    public Connecter(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.empreinteFactice = encodeurMotDePasse.encoder(new MotDePasse(valeurAleatoire()));
    }

    /**
     * @throws DonneesCompteInvalidesException pseudo et/ou mot de passe absents
     * @throws IdentifiantsInvalidesException  pour tout autre échec, quelle qu'en soit la cause
     */
    @Transactional(readOnly = true)
    public Compte executer(String saisiePseudo, String saisieMotDePasse) {
        verifierPresence(saisiePseudo, saisieMotDePasse);
        if (Pseudo.excedeLongueurMax(saisiePseudo) || MotDePasse.excedeLongueurMax(saisieMotDePasse)) {
            throw new IdentifiantsInvalidesException();
        }
        Optional<Compte> compte = depotComptes.trouverParPseudoNormalise(Pseudo.normaliser(saisiePseudo))
                .filter(Compte::peutSeConnecter);
        String empreinte = compte.map(Compte::empreinteMotDePasse).orElse(empreinteFactice);
        boolean motDePasseCorrect = encodeurMotDePasse.verifier(saisieMotDePasse, empreinte);
        return compte.filter(trouve -> motDePasseCorrect).orElseThrow(IdentifiantsInvalidesException::new);
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
