package fr.backyard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.util.Comparator;

/**
 * Coureur inscrit à une course. La colonne {@code runner.name} n'est plus mappée depuis l'incrément 5 : elle reste
 * vide et n'est jamais écrite (RG6, RG18 inc. 5) ; le nom affiché est dérivé par {@link #displayName()}.
 */
@Entity
@Table(
    name = "runner",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_runner_race_bib", columnNames = {"race_id", "bib"}),
        @UniqueConstraint(name = "uq_runner_qr_token", columnNames = {"qr_token"}),
        @UniqueConstraint(name = "uq_runner_race_account", columnNames = {"race_id", "account_id"})
    }
)
public class Runner {

    /** Ordre d'affichage des coureurs d'une course : dossard croissant (RG17 et RG24 inc. 3). */
    public static final Comparator<Runner> BIB_ORDER = Comparator.comparingInt(Runner::getBib);

    /** Préfixe du nom affiché d'un coureur sans compte (RG18 inc. 5). */
    public static final String NO_ACCOUNT_NAME_PREFIX = "Coureur n°";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "race_id", nullable = false, foreignKey = @ForeignKey(name = "fk_runner_race"))
    private Race race;

    /**
     * Compte du coureur (RG4 inc. 5) : null pour un coureur antérieur à l'incrément 5 ou détaché par la suppression
     * de son compte. Chargé avec le coureur, puisque le nom affiché en dépend (RG18).
     */
    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "account_id", foreignKey = @ForeignKey(name = "fk_runner_account"))
    private Account account;

    @Positive
    @Column(nullable = false)
    private int bib;

    @NotBlank
    @Column(name = "qr_token", nullable = false, length = 36)
    private String qrToken;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunnerStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "dnf_reason", length = 10)
    private DnfReason dnfReason;

    @Column(name = "dnf_yard")
    private Integer dnfYard;

    protected Runner() {
    }

    /** Coureur sans compte (données antérieures à l'incrément 5, jeux de test). */
    public Runner(Race race, int bib, String qrToken) {
        this(race, bib, null, qrToken);
    }

    /** Coureur lié à un compte (RG7, RG8 inc. 5) ; le lien n'est plus modifié, sauf par {@link #detachAccount()}. */
    public Runner(Race race, int bib, Account account, String qrToken) {
        this.race = race;
        this.bib = bib;
        this.account = account;
        this.qrToken = qrToken;
        this.status = RunnerStatus.ACTIVE;
    }

    /**
     * Unique calcul du nom affiché d'un coureur (RG18 inc. 5) : le pseudo de son compte, tel que stocké, ou
     * « Coureur n°{bib} » sans compte (dossard actuel, en décimal).
     */
    public String displayName() {
        return account == null ? NO_ACCOUNT_NAME_PREFIX + bib : account.getPseudo();
    }

    /** Seule modification du lien au compte (RG4, RG20 inc. 5) : passage à null à la suppression du compte. */
    public void detachAccount() {
        this.account = null;
    }

    /** Identifiant du compte lié, ou null sans compte (RG12 inc. 5). */
    public Long accountId() {
        return account == null ? null : account.getId();
    }

    /** Pseudo du compte lié, ou null sans compte (RG12 inc. 5). */
    public String accountPseudo() {
        return account == null ? null : account.getPseudo();
    }

    /**
     * Transition unique ACTIVE vers DNF (RG29), utilisée par l'auto-DNF et le DNF manuel.
     */
    public void markDnf(DnfReason reason, int yard) {
        requireStatus(RunnerStatus.ACTIVE, "passer DNF");
        if (reason == null) {
            throw new IllegalArgumentException("Raison de DNF obligatoire pour le coureur " + id);
        }
        if (yard < 1) {
            throw new IllegalArgumentException("Yard de DNF invalide pour le coureur " + id + " : " + yard);
        }
        this.status = RunnerStatus.DNF;
        this.dnfReason = reason;
        this.dnfYard = yard;
    }

    /**
     * Seule transition DNF vers ACTIVE (RG29), utilisée par la réintégration et la réactivation automatique.
     */
    public void reactivate() {
        requireStatus(RunnerStatus.DNF, "être réactivé");
        this.status = RunnerStatus.ACTIVE;
        this.dnfReason = null;
        this.dnfYard = null;
    }

    /**
     * Transition ACTIVE vers WINNER (RG21, RG29).
     */
    public void markWinner() {
        requireStatus(RunnerStatus.ACTIVE, "être déclaré vainqueur");
        this.status = RunnerStatus.WINNER;
        this.dnfReason = null;
        this.dnfYard = null;
    }

    public boolean isActive() {
        return status == RunnerStatus.ACTIVE;
    }

    private void requireStatus(RunnerStatus expected, String action) {
        if (status != expected) {
            throw new IllegalStateException("Le coureur " + id + " ne peut pas " + action
                + " : statut " + status + ", attendu " + expected);
        }
    }

    public Long getId() {
        return id;
    }

    public Race getRace() {
        return race;
    }

    public void setRace(Race race) {
        this.race = race;
    }

    public Account getAccount() {
        return account;
    }

    public int getBib() {
        return bib;
    }

    public void setBib(int bib) {
        this.bib = bib;
    }

    public String getQrToken() {
        return qrToken;
    }

    public void setQrToken(String qrToken) {
        this.qrToken = qrToken;
    }

    public RunnerStatus getStatus() {
        return status;
    }

    public void setStatus(RunnerStatus status) {
        this.status = status;
    }

    public DnfReason getDnfReason() {
        return dnfReason;
    }

    public void setDnfReason(DnfReason dnfReason) {
        this.dnfReason = dnfReason;
    }

    public Integer getDnfYard() {
        return dnfYard;
    }

    public void setDnfYard(Integer dnfYard) {
        this.dnfYard = dnfYard;
    }
}
