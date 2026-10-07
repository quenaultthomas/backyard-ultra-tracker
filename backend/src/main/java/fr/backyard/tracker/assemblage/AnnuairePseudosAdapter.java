package fr.backyard.tracker.assemblage;

import fr.backyard.tracker.comptes.application.LirePseudos;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Pont entre les contextes : implémente le port AnnuairePseudos de courses en lisant les Comptes de comptes.
 * Exception nommée des règles ArchUnit (3.5, RG6), comme {@link AnnuaireBenevolesAdapter}.
 */
@Component
public class AnnuairePseudosAdapter implements AnnuairePseudos {

    private final LirePseudos lirePseudos;

    public AnnuairePseudosAdapter(LirePseudos lirePseudos) {
        this.lirePseudos = lirePseudos;
    }

    @Override
    public Map<UUID, String> pseudosDe(Collection<UUID> idsComptes) {
        return lirePseudos.executer(idsComptes);
    }
}
