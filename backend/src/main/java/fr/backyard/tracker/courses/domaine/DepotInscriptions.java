package fr.backyard.tracker.courses.domaine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Port sortant de persistance des Inscriptions. Le Compte n'est connu que par son identifiant. */
public interface DepotInscriptions {

    /**
     * Ajoute une nouvelle Inscription.
     *
     * @throws InscriptionDejaExistanteException le Compte a déjà une Inscription à cette Course
     */
    void enregistrer(Inscription inscription);

    /** Plus grand dossard attribué dans la Course, vide si elle n'a aucune Inscription. */
    Optional<Integer> plusGrandDossard(UUID courseId);

    boolean existePour(UUID courseId, UUID compteId);

    /** Nombre d'Inscriptions de la Course, quel que soit leur statut ; ne compare à aucun maximum. */
    int nombreInscrits(UUID courseId);

    /** Inscriptions du Compte, toutes Courses confondues, dans un ordre quelconque. */
    List<Inscription> parCompte(UUID compteId);

    /** Inscription de cet identifiant si elle appartient au Compte, vide sinon (inconnue ou d'un autre Compte). */
    Optional<Inscription> parIdEtCompte(UUID inscriptionId, UUID compteId);

    /** Supprime définitivement l'Inscription ; une Inscription déjà absente ne supprime rien. */
    void supprimer(UUID inscriptionId);
}
