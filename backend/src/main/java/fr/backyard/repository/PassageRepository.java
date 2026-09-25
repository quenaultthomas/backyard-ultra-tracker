package fr.backyard.repository;

import fr.backyard.domain.Passage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PassageRepository extends JpaRepository<Passage, Long> {

    List<Passage> findByRunnerId(Long runnerId);

    Optional<Passage> findByRunnerIdAndYardNumber(Long runnerId, int yardNumber);

    boolean existsByRunnerId(Long runnerId);

    /** Tous les passages des coureurs d'une course, en une seule requête (tableau de bord). */
    List<Passage> findByRunnerRaceId(Long raceId);
}
