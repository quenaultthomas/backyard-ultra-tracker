package fr.backyard.repository;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RaceRepository extends JpaRepository<Race, Long> {

    List<Race> findByStatus(RaceStatus status);
}
