package fr.backyard.tracker.courses.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ligne de la table logo_course (au plus une par Course). Distincte du Logo du domaine ; le mapping est fait par
 * {@link LogoCourseJpaAdapter}. Lecture seule : l'écriture passe par une requête de remplacement atomique.
 */
@Entity
@Table(name = "logo_course")
public class LogoCourseJpaEntity {

    @Id
    @Column(name = "course_id")
    private UUID idCourse;

    /** bytea (et non Large Object) : sans @Lob. */
    @Column(name = "contenu", nullable = false)
    private byte[] contenu;

    @Column(name = "type_mime", nullable = false, length = 20)
    private String typeMime;

    @Column(name = "taille_octets", nullable = false)
    private int tailleOctets;

    @Column(name = "empreinte", nullable = false, length = 64)
    private String empreinte;

    protected LogoCourseJpaEntity() {
        // requis par JPA
    }

    byte[] contenu() {
        return contenu;
    }
}
