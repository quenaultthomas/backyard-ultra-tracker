package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.StatutInscription;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * Ligne de la table inscription. Distincte de l'Inscription du domaine ; le mapping est fait par
 * {@link InscriptionJpaAdapter}. La Course et le Compte ne sont que des identifiants (la base supprime les
 * Inscriptions avec leur Course ; aucune clé étrangère vers compte).
 */
@Entity
@Table(name = "inscription")
public class InscriptionJpaEntity {

    @Id
    private UUID id;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Column(name = "compte_id", nullable = false)
    private UUID compteId;

    @Column(name = "dossard", nullable = false)
    private int dossard;

    @Column(name = "jeton_qr", nullable = false, length = 64)
    private String jetonQr;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutInscription statut;

    protected InscriptionJpaEntity() {
        // requis par JPA
    }

    InscriptionJpaEntity(UUID id, UUID courseId, UUID compteId, int dossard, String jetonQr,
                         StatutInscription statut) {
        this.id = id;
        this.courseId = courseId;
        this.compteId = compteId;
        this.dossard = dossard;
        this.jetonQr = jetonQr;
        this.statut = statut;
    }

    UUID id() {
        return id;
    }

    UUID courseId() {
        return courseId;
    }

    UUID compteId() {
        return compteId;
    }

    int dossard() {
        return dossard;
    }

    String jetonQr() {
        return jetonQr;
    }

    StatutInscription statut() {
        return statut;
    }
}
