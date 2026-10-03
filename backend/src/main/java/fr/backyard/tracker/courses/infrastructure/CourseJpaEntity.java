package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.StatutCourse;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Ligne de la table course et ses lignes affectation_benevole. Distincte de la Course du domaine ; le mapping est
 * fait par {@link CourseJpaAdapter}.
 */
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

    /** Identifiants de Comptes, sans clé étrangère vers compte (contextes séparés). */
    @ElementCollection
    @CollectionTable(name = "affectation_benevole", joinColumns = @JoinColumn(name = "course_id"))
    @Column(name = "benevole_id", nullable = false)
    private Set<UUID> benevolesAffectes = new HashSet<>();

    protected CourseJpaEntity() {
        // requis par JPA
    }

    CourseJpaEntity(UUID id, String nom, LocalDate date, StatutCourse statut, int distanceBoucleMetres,
                    int dureeBoucleMinutes, int denivelePositifBoucleMetres, int nombreMaxParticipants,
                    int nombreMaxBoucles, Collection<UUID> benevolesAffectes) {
        this.id = id;
        this.nom = nom;
        this.date = date;
        this.statut = statut;
        this.distanceBoucleMetres = distanceBoucleMetres;
        this.dureeBoucleMinutes = dureeBoucleMinutes;
        this.denivelePositifBoucleMetres = denivelePositifBoucleMetres;
        this.nombreMaxParticipants = nombreMaxParticipants;
        this.nombreMaxBoucles = nombreMaxBoucles;
        this.benevolesAffectes.addAll(benevolesAffectes);
    }

    /** Mise à jour de la ligne avec les valeurs d'une autre représentation de la même Course (l'id est conservé). */
    void remplacerPar(CourseJpaEntity source) {
        this.nom = source.nom;
        this.date = source.date;
        this.statut = source.statut;
        this.distanceBoucleMetres = source.distanceBoucleMetres;
        this.dureeBoucleMinutes = source.dureeBoucleMinutes;
        this.denivelePositifBoucleMetres = source.denivelePositifBoucleMetres;
        this.nombreMaxParticipants = source.nombreMaxParticipants;
        this.nombreMaxBoucles = source.nombreMaxBoucles;
        remplacerBenevolesAffectes(source.benevolesAffectes);
    }

    /** Seules les différences sont écrites : un ensemble inchangé ne touche aucune ligne. */
    private void remplacerBenevolesAffectes(Set<UUID> nouveaux) {
        benevolesAffectes.retainAll(nouveaux);
        benevolesAffectes.addAll(nouveaux);
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

    Set<UUID> benevolesAffectes() {
        return benevolesAffectes;
    }
}
