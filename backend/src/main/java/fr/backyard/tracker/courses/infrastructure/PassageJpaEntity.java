package fr.backyard.tracker.courses.infrastructure;

import fr.backyard.tracker.courses.domaine.OriginePassage;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Ligne de la table passage. Distincte du Passage du domaine ; le mapping est fait par {@link PassageJpaAdapter}.
 * Jamais modifiée après insertion. L'Inscription n'est qu'un identifiant (la base supprime les Passages avec elle).
 */
@Entity
@Table(name = "passage")
public class PassageJpaEntity {

    @Id
    private UUID id;

    @Column(name = "inscription_id", nullable = false, updatable = false)
    private UUID inscriptionId;

    @Column(name = "numero_boucle", nullable = false, updatable = false)
    private int numeroBoucle;

    @Column(name = "scanne_le", updatable = false)
    private Instant scanneLe;

    @Enumerated(EnumType.STRING)
    @Column(name = "origine", nullable = false, length = 20, updatable = false)
    private OriginePassage origine;

    protected PassageJpaEntity() {
        // requis par JPA
    }

    PassageJpaEntity(UUID id, UUID inscriptionId, int numeroBoucle, Instant scanneLe, OriginePassage origine) {
        this.id = id;
        this.inscriptionId = inscriptionId;
        this.numeroBoucle = numeroBoucle;
        this.scanneLe = scanneLe;
        this.origine = origine;
    }

    UUID id() {
        return id;
    }

    UUID inscriptionId() {
        return inscriptionId;
    }

    int numeroBoucle() {
        return numeroBoucle;
    }

    Instant scanneLe() {
        return scanneLe;
    }

    OriginePassage origine() {
        return origine;
    }
}
