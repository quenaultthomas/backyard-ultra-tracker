package fr.backyard.tracker.comptes.application;

import static fr.backyard.tracker.comptes.domaine.ConfigurationAdminMasterInvalideException.VARIABLE_MOT_DE_PASSE;
import static fr.backyard.tracker.comptes.domaine.ConfigurationAdminMasterInvalideException.VARIABLE_PSEUDO;
import static fr.backyard.tracker.comptes.domaine.ConfigurationAdminMasterInvalideException.pourVariable;

import fr.backyard.tracker.comptes.domaine.AdminMasterDejaPresentException;
import fr.backyard.tracker.comptes.domaine.Compte;
import fr.backyard.tracker.comptes.domaine.ConfigurationAdminMasterInvalideException;
import fr.backyard.tracker.comptes.domaine.DepotComptes;
import fr.backyard.tracker.comptes.domaine.EncodeurMotDePasse;
import fr.backyard.tracker.comptes.domaine.MotDePasse;
import fr.backyard.tracker.comptes.domaine.Pseudo;
import fr.backyard.tracker.comptes.domaine.PseudoDejaUtiliseException;
import fr.backyard.tracker.comptes.domaine.ViolationValidation;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

/**
 * Création idempotente de l'unique admin master à partir de la configuration, exécutée à chaque démarrage.
 * Les journaux ne contiennent jamais le mot de passe ni le pseudo, seulement l'identifiant du compte.
 *
 * <p>Sans transaction englobante : l'enregistrement a sa propre transaction (adaptateur), annulée en cas
 * de conflit, ce qui permet de traiter une création concurrente comme un admin master déjà présent.
 */
@Service
public class InitialiserAdminMaster {

    /** Issue de l'initialisation (aucune donnée sensible). */
    public enum Resultat {
        CREE,
        DEJA_PRESENT,
        NON_CONFIGURE
    }

    private static final Logger JOURNAL = System.getLogger(InitialiserAdminMaster.class.getName());

    private final DepotComptes depotComptes;
    private final EncodeurMotDePasse encodeurMotDePasse;
    private final Clock horloge;

    public InitialiserAdminMaster(DepotComptes depotComptes, EncodeurMotDePasse encodeurMotDePasse, Clock horloge) {
        this.depotComptes = depotComptes;
        this.encodeurMotDePasse = encodeurMotDePasse;
        this.horloge = horloge;
    }

    /**
     * @throws ConfigurationAdminMasterInvalideException variable manquante, saisie invalide ou pseudo déjà pris
     */
    public Resultat executer(String saisiePseudo, String saisieMotDePasse) {
        if (depotComptes.existeAdminMaster()) {
            return dejaPresent();
        }
        boolean pseudoRenseigne = Pseudo.verifierPresence(saisiePseudo).isEmpty();
        boolean motDePasseRenseigne = MotDePasse.verifierPresence(saisieMotDePasse).isEmpty();
        if (!pseudoRenseigne && !motDePasseRenseigne) {
            JOURNAL.log(Level.WARNING, "Aucun admin master n'existe et " + VARIABLE_PSEUDO + " / "
                    + VARIABLE_MOT_DE_PASSE + " ne sont pas renseignées");
            return Resultat.NON_CONFIGURE;
        }
        if (!pseudoRenseigne || !motDePasseRenseigne) {
            throw ConfigurationAdminMasterInvalideException.variableManquante(
                    pseudoRenseigne ? VARIABLE_MOT_DE_PASSE : VARIABLE_PSEUDO);
        }
        verifierSaisies(saisiePseudo, saisieMotDePasse);
        return creer(new Pseudo(saisiePseudo), new MotDePasse(saisieMotDePasse));
    }

    private Resultat creer(Pseudo pseudo, MotDePasse motDePasse) {
        if (depotComptes.existeParPseudoNormalise(pseudo.normalise())) {
            throw ConfigurationAdminMasterInvalideException.pseudoDejaUtilise();
        }
        Compte compte = Compte.creerAdminMaster(pseudo, encodeurMotDePasse.encoder(motDePasse), maintenant());
        try {
            depotComptes.enregistrer(compte);
        } catch (AdminMasterDejaPresentException conflit) {
            return dejaPresent();
        } catch (PseudoDejaUtiliseException conflit) {
            return pseudoPrisEntreTemps();
        }
        JOURNAL.log(Level.INFO, "Admin master créé (compte " + compte.id() + ")");
        return Resultat.CREE;
    }

    /** Démarrage concurrent avec le même pseudo : l'index du pseudo peut être contrôlé avant celui du rôle. */
    private Resultat pseudoPrisEntreTemps() {
        if (depotComptes.existeAdminMaster()) {
            return dejaPresent();
        }
        throw ConfigurationAdminMasterInvalideException.pseudoDejaUtilise();
    }

    private static Resultat dejaPresent() {
        JOURNAL.log(Level.INFO, "Admin master déjà présent");
        return Resultat.DEJA_PRESENT;
    }

    /** Précision de la base (timestamptz), comme pour la création d'un compte coureur. */
    private Instant maintenant() {
        return horloge.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static void verifierSaisies(String saisiePseudo, String saisieMotDePasse) {
        List<ViolationValidation> violations = Stream.concat(
                        Pseudo.verifier(saisiePseudo).map(violation -> pourVariable(VARIABLE_PSEUDO, violation))
                                .stream(),
                        MotDePasse.verifier(saisieMotDePasse)
                                .map(violation -> pourVariable(VARIABLE_MOT_DE_PASSE, violation)).stream())
                .toList();
        if (!violations.isEmpty()) {
            throw new ConfigurationAdminMasterInvalideException(violations);
        }
    }
}
