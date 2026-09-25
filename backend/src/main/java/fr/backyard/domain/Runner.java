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

@Entity
@Table(
    name = "runner",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_runner_race_bib", columnNames = {"race_id", "bib"}),
        @UniqueConstraint(name = "uq_runner_qr_token", columnNames = {"qr_token"})
    }
)
public class Runner {

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
