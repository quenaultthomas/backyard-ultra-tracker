package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.StatutCourse;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.UUID;

/** Ligne de la table course. Distincte de la Course du domaine ; le mapping est fait par {@link CourseJpaAdapter}. */
@Entity
@Table(name = "course")
public class CourseJpaEntity {

    @Id
    private UUID id;

    @Column(name = "nom", nullable = false, length = 100)
    private String nom;

    @Column(name = "date_course", nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutCourse statut;

    @Column(name = "distance_boucle_metres", nullable = false)
    private int distanceBoucleMetres;

    @Column(name = "duree_boucle_minutes", nullable = false)
    private int dureeBoucleMinutes;

    @Column(name = "denivele_positif_boucle_metres", nullable = false)
    private int denivelePositifBoucleMetres;

    @Column(name = "nombre_max_participants", nullable = false)
    private int nombreMaxParticipants;

    @Column(name = "nombre_max_boucles", nullable = false)
    private int nombreMaxBoucles;

    protected CourseJpaEntity() {
        // requis par JPA
    }

    CourseJpaEntity(UUID id, String nom, LocalDate date, StatutCourse statut, int distanceBoucleMetres,
                    int dureeBoucleMinutes, int denivelePositifBoucleMetres, int nombreMaxParticipants,
                    int nombreMaxBoucles) {
        this.id = id;
        this.nom = nom;
        this.date = date;
        this.statut = statut;
        this.distanceBoucleMetres = distanceBoucleMetres;
        this.dureeBoucleMinutes = dureeBoucleMinutes;
        this.denivelePositifBoucleMetres = denivelePositifBoucleMetres;
        this.nombreMaxParticipants = nombreMaxParticipants;
        this.nombreMaxBoucles = nombreMaxBoucles;
    }

    UUID id() {
        return id;
    }

    String nom() {
        return nom;
    }

    LocalDate date() {
        return date;
    }

    StatutCourse statut() {
        return statut;
    }

    int distanceBoucleMetres() {
        return distanceBoucleMetres;
    }

    int dureeBoucleMinutes() {
        return dureeBoucleMinutes;
    }

    int denivelePositifBoucleMetres() {
        return denivelePositifBoucleMetres;
    }

    int nombreMaxParticipants() {
        return nombreMaxParticipants;
    }

    int nombreMaxBoucles() {
        return nombreMaxBoucles;
    }
}
