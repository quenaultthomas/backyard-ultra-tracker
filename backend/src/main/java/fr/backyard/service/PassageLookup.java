package fr.backyard.service;

import fr.backyard.domain.Passage;
import fr.backyard.domain.Runner;
import fr.backyard.repository.PassageRepository;

import java.util.List;
import java.util.Optional;

/**
 * Lecture des passages d'un coureur. Porte l'unique définition de "passage valide sur le yard N"
 * (spec incrément 2, section 2) : un passage du coureur avec ce numéro de yard, quelle que soit sa source.
 */
final class PassageLookup {

    private final PassageRepository passageRepository;

    PassageLookup(PassageRepository passageRepository) {
        this.passageRepository = passageRepository;
    }

    Optional<Passage> onYard(Runner runner, int yard) {
        return passageRepository.findByRunnerIdAndYardNumber(runner.getId(), yard);
    }

    boolean hasValidPassageOnYard(Runner runner, int yard) {
        return onYard(runner, yard).isPresent();
    }

    List<Passage> allOf(Runner runner) {
        return passageRepository.findByRunnerId(runner.getId());
    }
}
