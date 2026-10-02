package fr.backyard.tracker.comptes.domaine;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Identité d'une personne qui se connecte (racine d'agrégat du contexte comptes).
 * Ne contient jamais le mot de passe en clair, seulement son empreinte.
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
        this.empreinteMotDePasse = Objects.requireNonNull(empreinteMotDePasse);
        this.role = Objects.requireNonNull(role);
        this.creeLe = Objects.requireNonNull(creeLe);
    }

    /** Les comptes créés librement sont toujours des comptes coureurs. */
    public static Compte creerCoureur(Pseudo pseudo, String empreinteMotDePasse, Instant creeLe) {
        return new Compte(UUID.randomUUID(), pseudo, empreinteMotDePasse, Role.COUREUR, creeLe);
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
