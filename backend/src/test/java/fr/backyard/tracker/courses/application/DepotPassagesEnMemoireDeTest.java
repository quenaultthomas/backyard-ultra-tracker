package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.PassageDejaEnregistreException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Double en mémoire du port DepotPassages (4.4a). Reproduit la contrainte d'unicité (inscription, boucle) de la base :
 * {@code enregistrer} lève {@link PassageDejaEnregistreException} sur un doublon. {@link #echecAEnregistrer} simule
 * une course entre deux scans simultanés : la vérification {@code existe} a répondu faux, mais l'écriture échoue.
 */
final class DepotPassagesEnMemoireDeTest implements DepotPassages {

    final List<Passage> passages = new ArrayList<>();
    int nombreDEnregistrements;
    RuntimeException echecAEnregistrer;
    int nombreDeLecturesGroupees;

    /** Pose un Passage préexistant, sans compter d'écriture du cas d'usage. */
    void poser(Passage passage) {
        passages.add(passage);
    }

    /** Lecture groupée de 4.7 (port DepotPassages.parInscriptions, nom supposé). */
    public List<Passage> parInscriptions(java.util.Collection<UUID> inscriptionIds) {
        nombreDeLecturesGroupees++;
        return passages.stream().filter(p -> inscriptionIds.contains(p.inscriptionId())).toList();
    }

    @Override
    public Optional<Passage> parId(UUID id) {
        return passages.stream().filter(p -> p.id().equals(id)).findFirst();
    }

    @Override
    public boolean existe(UUID inscriptionId, int numeroBoucle) {
        if (echecAEnregistrer != null) {
            return false;
        }
        return passages.stream()
                .anyMatch(p -> p.inscriptionId().equals(inscriptionId) && p.numeroBoucle() == numeroBoucle);
    }

    @Override
    public Optional<Passage> parInscriptionEtBoucle(UUID inscriptionId, int numeroBoucle) {
        return passages.stream()
                .filter(p -> p.inscriptionId().equals(inscriptionId) && p.numeroBoucle() == numeroBoucle)
                .findFirst();
    }

    @Override
    public void enregistrer(Passage passage) {
        nombreDEnregistrements++;
        if (echecAEnregistrer != null) {
            throw echecAEnregistrer;
        }
        boolean doublon = passages.stream().anyMatch(p -> p.inscriptionId().equals(passage.inscriptionId())
                && p.numeroBoucle() == passage.numeroBoucle());
        if (doublon) {
            throw org.mockito.Mockito.mock(PassageDejaEnregistreException.class);
        }
        passages.add(passage);
    }
}
