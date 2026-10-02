package fr.backyard.tracker.comptes.application;

import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.DonneesCompteInvalidesException;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Création libre d'un compte coureur par un visiteur. */
@Service
public class CreerCompteCoureur {

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final Clock horloge;

    public CreerCompteCoureur(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.horloge = horloge;
    }

    /**
     * @throws DonneesCompteInvalidesException toutes les violations de saisie, avant toute vérification d'unicité
     * @throws PseudoDejaUtiliseException      si le pseudo est déjà pris, quelle qu'en soit la casse
     */
    @Transactional
    public Compte executer(String saisiePseudo, String saisieMotDePasse) {
        verifierSaisies(saisiePseudo, saisieMotDePasse);
        Pseudo pseudo = new Pseudo(saisiePseudo);
        if (depotComptes.existeParPseudoNormalise(pseudo.normalise())) {
            throw new PseudoDejaUtiliseException();
        }
        String empreinte = encodeurMotDePasse.encoder(new MotDePasse(saisieMotDePasse));
        Compte compte = Compte.creerCoureur(pseudo, empreinte, maintenant());
        depotComptes.enregistrer(compte);
        return compte;
    }

    /** Précision de la base (timestamptz) : l'instant renvoyé est celui qui est stocké. */
    private Instant maintenant() {
        return horloge.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void verifierSaisies(String saisiePseudo, String saisieMotDePasse) {
        List<ViolationValidation> violations = Stream.of(
                        Pseudo.verifier(saisiePseudo),
                        MotDePasse.verifier(saisieMotDePasse))
                .flatMap(Optional::stream)
                .toList();
        if (!violations.isEmpty()) {
            throw new DonneesCompteInvalidesException(violations);
        }
    }
}
