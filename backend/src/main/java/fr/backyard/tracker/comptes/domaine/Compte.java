package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;
import java.util.Comparator;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité d'une personne qui se connecte (racine d'agrégat du contexte comptes).
 * Ne contient jamais le mot de passe en clair, seulement son empreinte, absente pour un compte anonymisé.
 */
public final class Compte {

    /** Ordre d'affichage des listes de comptes : date de création, puis pseudo normalisé. */
    public static final Comparator<Compte> ORDRE_DE_CREATION =
            Comparator.comparing(Compte::creeLe).thenComparing(Compte::pseudoNormalise);

    /** Longueur de la clé d'unicité d'un Compte anonymisé : « # » puis des chiffres hexadécimaux de l'id. */
    private static final int LONGUEUR_CLE_ANONYME = 30;
    private static final String PREFIXE_CLE_ANONYME = "#";

    private final UUID id;
    private final Pseudo pseudo;
    /** Clé d'unicité : pseudo normalisé, ou clé propre au Compte s'il est anonymisé. */
    private final String pseudoNormalise;
    private final String empreinteMotDePasse;
    private final Role role;
    private final Instant creeLe;

    private Compte(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe) {
        this.id = Objects.requireNonNull(id);
        this.pseudo = Objects.requireNonNull(pseudo);
        this.pseudoNormalise = pseudo.estAnonyme() ? cleAnonyme(id) : pseudo.normalise();
        this.empreinteMotDePasse = empreinteMotDePasse;
        this.role = Objects.requireNonNull(role);
        this.creeLe = Objects.requireNonNull(creeLe);
    }

    /** Les comptes créés librement sont toujours des comptes coureurs. */
    public static Compte creerCoureur(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.COUREUR,
                creeLe);
    }

    /** Les comptes administrateurs sont créés par l'admin master, toujours avec le rôle ADMIN. */
    public static Compte creerAdmin(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN,
                creeLe);
    }

    /** Les comptes bénévoles sont créés par un admin (ou l'admin master), toujours avec le rôle BENEVOLE. */
    public static Compte creerBenevole(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.BENEVOLE,
                creeLe);
    }

    /** L'unique admin master, créé au premier démarrage à partir de la configuration, jamais depuis l'interface. */
    public static Compte creerAdminMaster(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, Objects.requireNonNull(empreinteMotDePasse), Role.ADMIN_MASTER,
                creeLe);
    }

    /** Recharge un compte existant ; l'empreinte est nulle pour un compte anonymisé. */
    public static Compte reconstituer(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe) {
        return new Compte(id, pseudo, empreinteMotDePasse, role, creeLe);
    }

    /** Un compte sans empreinte (anonymisé) ne peut jamais se connecter, quel que soit le mot de passe. */
    public boolean peutSeConnecter() {
        return empreinteMotDePasse != null;
    }

    /**
     * Remplace l'empreinte du mot de passe ; identité, pseudo, rôle et date de création inchangés.
     * Une empreinte est exigée : un changement ne rend jamais un compte inutilisable.
     */
    public Compte changerMotDePasse(String nouvelleEmpreinte) {
        return new Compte(id, pseudo, Objects.requireNonNull(nouvelleEmpreinte), role, creeLe);
    }

    /**
     * Suppression d'un Compte coureur par lui-même : même identifiant, rôle et date de création, pseudo
     * {@link Pseudo#anonyme()}, clé d'unicité propre au Compte (le pseudo d'origine est libéré), plus d'empreinte
     * (connexion impossible). Unique contrôle de la règle « seul un coureur supprime son Compte ».
     *
     * @throws SuppressionCompteInterditeException le Compte n'est pas un Compte coureur
     */
    public Compte anonymiser() {
        if (role != Role.COUREUR) {
            throw new SuppressionCompteInterditeException();
        }
        return new Compte(id, Pseudo.anonyme(), null, role, creeLe);
    }

    public boolean estAnonyme() {
        return pseudo.estAnonyme();
    }

    /** « # » (refusé à la saisie d'un pseudo) suivi des premiers chiffres hexadécimaux de l'identifiant. */
    private static String cleAnonyme(UUID id) {
        String hexadecimal = id.toString().replace("-", "");
        return PREFIXE_CLE_ANONYME + hexadecimal.substring(0, LONGUEUR_CLE_ANONYME - PREFIXE_CLE_ANONYME.length());
    }

    public UUID id() {
        return id;
    }

    public Pseudo pseudo() {
        return pseudo;
    }

    public String pseudoNormalise() {
        return pseudoNormalise;
    }

    /** Nulle si le compte ne peut pas se connecter ({@link #peutSeConnecter()}). */
    public String empreinteMotDePasse() {
        return empreinteMotDePasse;
    }

    public Role role() {
        return role;
    }

    public Instant creeLe() {
        return creeLe;
    }

    @Override
    public boolean equals(Object autre) {
        return autre instanceof Compte compte && id.equals(compte.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Compte[id=" + id + ", pseudo=" + pseudo.valeur() + ", role=" + role + "]";
    }
}
