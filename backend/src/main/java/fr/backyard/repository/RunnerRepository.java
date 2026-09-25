package fr.backyard.repository;

import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RunnerRepository extends JpaRepository<Runner, Long> {

    List<Runner> findByRaceId(Long raceId);

    Optional<Runner> findByQrToken(String qrToken);

    List<Runner> findByRaceIdAndStatus(Long raceId, RunnerStatus status);

    boolean existsByRaceIdAndBib(Long raceId, int bib);

    boolean existsByRaceIdAndBibAndIdNot(Long raceId, int bib, Long id);
}
