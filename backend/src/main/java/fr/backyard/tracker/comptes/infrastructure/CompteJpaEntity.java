package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.Role;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Ligne de la table compte. Distincte du Compte du domaine ; le mapping est fait par {@link CompteJpaAdapter}. */
@Entity
@Table(name = "compte")
public class CompteJpaEntity {

    @Id
    private UUID id;

    @Column(name = "pseudo", nullable = false, length = 30)
    private String pseudo;

    @Column(name = "pseudo_normalise", nullable = false, length = 30, unique = true)
    private String pseudoNormalise;

    @Column(name = "empreinte_mot_de_passe")
    private String empreinteMotDePasse;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private Role role;

    @Column(name = "cree_le", nullable = false)
    private Instant creeLe;

    protected CompteJpaEntity() {
        // requis par JPA
    }

    CompteJpaEntity(UUID id, String pseudo, String pseudoNormalise, String empreinteMotDePasse, Role role,
                    Instant creeLe) {
        this.id = id;
        this.pseudo = pseudo;
        this.pseudoNormalise = pseudoNormalise;
        this.empreinteMotDePasse = empreinteMotDePasse;
        this.role = role;
        this.creeLe = creeLe;
    }

    /** Seule donnée modifiable d'un compte existant (changement de mot de passe). */
    void remplacerEmpreinte(String nouvelleEmpreinte) {
        this.empreinteMotDePasse = nouvelleEmpreinte;
    }

    UUID id() {
        return id;
    }

    String pseudo() {
        return pseudo;
    }

    String empreinteMotDePasse() {
        return empreinteMotDePasse;
    }

    Role role() {
        return role;
    }

    Instant creeLe() {
        return creeLe;
    }
}
