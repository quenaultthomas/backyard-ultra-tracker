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

/**
 * Déroulé commun de la création d'un compte depuis une saisie (coureur, admin) : validation complète,
 * puis unicité du pseudo, puis hachage et enregistrement. Le rôle est fixé par la fabrique du domaine.
 */
final class CreationCompte {

    /** Fabrique du domaine qui fixe le rôle du compte créé ({@code Compte::creerCoureur}, {@code Compte::creerAdmin}). */
    @FunctionalInterface
    interface FabriqueCompte {
        Compte creer(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe);
    }

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final Clock horloge;

    CreationCompte(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.horloge = horloge;
    }

    /**
     * @throws DonneesCompteInvalidesException toutes les violations de saisie, avant toute vérification d'unicité
     * @throws PseudoDejaUtiliseException      si le pseudo est déjà pris, quelle qu'en soit la casse
     */
    Compte creer(String saisiePseudo, String saisieMotDePasse, FabriqueCompte fabrique) {
        verifierSaisies(saisiePseudo, saisieMotDePasse);
        Pseudo pseudo = new Pseudo(saisiePseudo);
        if (depotComptes.existeParPseudoNormalise(pseudo.normalise())) {
            throw new PseudoDejaUtiliseException();
        }
        String empreinte = encodeurMotDePasse.encoder(new MotDePasse(saisieMotDePasse));
        Compte compte = fabrique.creer(pseudo, empreinte, maintenant());
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
