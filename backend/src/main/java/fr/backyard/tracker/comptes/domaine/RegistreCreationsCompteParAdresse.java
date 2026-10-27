package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;

/**
 * Port sortant : tentatives de création de Compte coureur par adresse cliente (SEC-15), distinctes des échecs de
 * connexion. Aucune opération d'effacement : seul l'écoulement de la fenêtre libère une adresse.
 */
public interface RegistreCreationsCompteParAdresse {

    /**
     * Atomique par adresse : refuse sans rien enregistrer si l'adresse est plafonnée à cet instant (règle de
     * {@link EchecsParAdresse}), sinon enregistre la tentative et l'admet.
     */
    AdmissionCreationCompte admettre(AdresseSuivie adresse, Instant maintenant, PlafondEchecsParAdresse plafond);
}
