package fr.backyard.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "race")
public class Race {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Column(nullable = false, unique = true)
    private String name;

    @NotNull
    @Column(name = "race_date", nullable = false)
    private LocalDate raceDate;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RaceStatus status;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "loop_distance", nullable = false)
    private int loopDistance;

    @Column(name = "loop_duration", nullable = false)
    private int loopDuration;

    @PositiveOrZero
    @Column(name = "loop_elevation", nullable = false)
    private int loopElevation;

    protected Race() {
    }

    public Race(String name, LocalDate raceDate, int loopDistance, int loopDuration, int loopElevation) {
        this.name = name;
        this.raceDate = raceDate;
        this.loopDistance = loopDistance;
        this.loopDuration = loopDuration;
        this.loopElevation = loopElevation;
        this.status = RaceStatus.SETUP;
    }

    /**
     * Transition unique RUNNING vers FINISHED (RG21, RG29).
     */
    public void finish() {
        if (status != RaceStatus.RUNNING) {
            throw new IllegalStateException("La course " + id + " ne peut pas être terminée : statut "
                + status + ", attendu " + RaceStatus.RUNNING);
        }
        this.status = RaceStatus.FINISHED;
    }

    public boolean isRunning() {
        return status == RaceStatus.RUNNING;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public LocalDate getRaceDate() {
        return raceDate;
    }

    public void setRaceDate(LocalDate raceDate) {
        this.raceDate = raceDate;
    }

    public RaceStatus getStatus() {
        return status;
    }

    public void setStatus(RaceStatus status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt;
    }

    public int getLoopDistance() {
        return loopDistance;
    }

    public void setLoopDistance(int loopDistance) {
        this.loopDistance = loopDistance;
    }

    public int getLoopDuration() {
        return loopDuration;
    }

    public void setLoopDuration(int loopDuration) {
        this.loopDuration = loopDuration;
    }

    public int getLoopElevation() {
        return loopElevation;
    }

    public void setLoopElevation(int loopElevation) {
        this.loopElevation = loopElevation;
    }
}
