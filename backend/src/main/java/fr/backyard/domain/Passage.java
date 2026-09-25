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
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Entity
@Table(
    name = "passage",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_passage_runner_yard", columnNames = {"runner_id", "yard_number"})
    }
)
public class Passage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "runner_id", nullable = false, foreignKey = @ForeignKey(name = "fk_passage_runner"))
    private Runner runner;

    @Column(name = "scanned_at")
    private Instant scannedAt;

    @Min(1)
    @Column(name = "yard_number", nullable = false)
    private int yardNumber;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PassageSource source;

    protected Passage() {
    }

    public Passage(Runner runner, int yardNumber, PassageSource source, Instant scannedAt) {
        this.runner = runner;
        this.yardNumber = yardNumber;
        this.source = source;
        this.scannedAt = scannedAt;
    }

    public Long getId() {
        return id;
    }

    public Runner getRunner() {
        return runner;
    }

    public Instant getScannedAt() {
        return scannedAt;
    }

    public int getYardNumber() {
        return yardNumber;
    }

    public PassageSource getSource() {
        return source;
    }
}
