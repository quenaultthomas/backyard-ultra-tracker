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

@Entity
@Table(
    name = "runner",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_runner_race_bib", columnNames = {"race_id", "bib"}),
        @UniqueConstraint(name = "uq_runner_qr_token", columnNames = {"qr_token"})
    }
)
public class Runner {

    /** Ordre d'affichage des coureurs d'une course : dossard croissant (RG17 et RG24 inc. 3). */
    public static final Comparator<Runner> BIB_ORDER = Comparator.comparingInt(Runner::getBib);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "race_id", nullable = false, foreignKey = @ForeignKey(name = "fk_runner_race"))
    private Race race;

    @Positive
    @Column(nullable = false)
    private int bib;

    @NotBlank
    @Column(nullable = false)
    private String name;

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

    public Runner(Race race, int bib, String name, String qrToken) {
        this.race = race;
        this.bib = bib;
        this.name = name;
        this.qrToken = qrToken;
        this.status = RunnerStatus.ACTIVE;
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

    public int getBib() {
        return bib;
    }

    public void setBib(int bib) {
        this.bib = bib;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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
