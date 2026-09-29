package fr.backyard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;

/**
 * Compte coureur « pseudo » (RG1 inc. 5) : exactement un identifiant, un pseudo normalisé (RG2) et le hash BCrypt
 * du mot de passe (RG3). Aucune autre donnée, aucun rôle. Le pseudo n'est pas modifiable.
 */
@Entity
@Table(
    name = "account",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_account_pseudo", columnNames = {"pseudo"})
    }
)
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, length = Pseudo.MAX_LENGTH)
    private String pseudo;

    @NotBlank
    @Column(name = "password_hash", nullable = false, length = 60)
    private String passwordHash;

    protected Account() {
    }

    /**
     * @param pseudo       pseudo déjà normalisé par {@link Pseudo#normalize(String)}
     * @param passwordHash hash BCrypt, jamais le mot de passe en clair
     */
    public Account(String pseudo, String passwordHash) {
        this.pseudo = pseudo;
        this.passwordHash = passwordHash;
    }

    /** Remplace le hash du mot de passe (RG14, RG19) : seule modification possible d'un compte. */
    public void replacePasswordHash(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
    }

    public Long getId() {
        return id;
    }

    public String getPseudo() {
        return pseudo;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    @Override
    public String toString() {
        return "Account[id=" + id + "]";
    }
}
