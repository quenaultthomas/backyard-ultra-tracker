package fr.backyard.tracker.comptes.infrastructure;

import fr.backyard.tracker.comptes.domaine.Role;
import fr.backyard.tracker.partage.domaine.FormatImage;
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

    @Column(name = "actif", nullable = false)
    private boolean actif;

    /** Jeton de la photo de profil (32 hexadécimaux minuscules), nul sans photo. */
    @Column(name = "photo_jeton", length = 32, unique = true)
    private String photoJeton;

    /** Format de la photo, nul si et seulement si le jeton l'est (contrainte ck_compte_photo_coherente). */
    @Enumerated(EnumType.STRING)
    @Column(name = "photo_format", length = 5)
    private FormatImage photoFormat;

    protected CompteJpaEntity() {
        // requis par JPA
    }

    CompteJpaEntity(UUID id, String pseudo, String pseudoNormalise, String empreinteMotDePasse, Role role,
                    Instant creeLe, boolean actif, String photoJeton, FormatImage photoFormat) {
        this.id = id;
        this.pseudo = pseudo;
        this.pseudoNormalise = pseudoNormalise;
        this.empreinteMotDePasse = empreinteMotDePasse;
        this.role = role;
        this.creeLe = creeLe;
        this.actif = actif;
        this.photoJeton = photoJeton;
        this.photoFormat = photoFormat;
    }

    /**
     * Données modifiables d'un compte existant (changement de mot de passe, anonymisation, désactivation ; rôle ramené
     * à COUREUR par l'anonymisation d'un bénévole ou d'un admin, 9.6a ; photo de profil, 10.1).
     */
    void reporter(String nouveauPseudo, String nouveauPseudoNormalise, String nouvelleEmpreinte, Role nouveauRole,
                  boolean nouvelActif, String nouveauPhotoJeton, FormatImage nouveauPhotoFormat) {
        this.pseudo = nouveauPseudo;
        this.pseudoNormalise = nouveauPseudoNormalise;
        this.empreinteMotDePasse = nouvelleEmpreinte;
        this.role = nouveauRole;
        this.actif = nouvelActif;
        this.photoJeton = nouveauPhotoJeton;
        this.photoFormat = nouveauPhotoFormat;
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

    boolean actif() {
        return actif;
    }

    String photoJeton() {
        return photoJeton;
    }

    FormatImage photoFormat() {
        return photoFormat;
    }
}
