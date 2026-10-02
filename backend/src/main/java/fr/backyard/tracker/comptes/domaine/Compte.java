package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité d'une personne qui se connecte (racine d'agrégat du contexte comptes).
 * Ne contient jamais le mot de passe en clair, seulement son empreinte, absente pour un compte anonymisé.
 */
public final class Compte {

    private final UUID id;
    private final Pseudo pseudo;
    private final String empreinteMotDePasse;
    private final Role role;
    private final Instant creeLe;

    private Compte(UUID id, Pseudo pseudo, String empreinteMotDePasse, Role role, Instant creeLe) {
        this.id = Objects.requireNonNull(id);
        this.pseudo = Objects.requireNonNull(pseudo);
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

    public UUID id() {
        return id;
    }

    public Pseudo pseudo() {
        return pseudo;
    }

    public String pseudoNormalise() {
        return pseudo.normalise();
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
