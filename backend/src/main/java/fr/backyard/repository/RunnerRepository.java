package fr.backyard.repository;

import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Coureurs. Les lectures de listes chargent le compte par jointure : le nom affiché en dépend (RG18 inc. 5).
 */
@Repository
public interface RunnerRepository extends JpaRepository<Runner, Long> {

    String ACCOUNT = "account";

    @EntityGraph(attributePaths = ACCOUNT)
    List<Runner> findByRaceId(Long raceId);

    @EntityGraph(attributePaths = ACCOUNT)
    Optional<Runner> findByQrToken(String qrToken);

    @EntityGraph(attributePaths = ACCOUNT)
    List<Runner> findByRaceIdAndStatus(Long raceId, RunnerStatus status);

    boolean existsByRaceIdAndBib(Long raceId, int bib);

    boolean existsByRaceIdAndBibAndIdNot(Long raceId, int bib, Long id);

    /** Coureurs d'un compte, toutes courses confondues (RG11, RG20 inc. 5). */
    @EntityGraph(attributePaths = {ACCOUNT, "race"})
    List<Runner> findByAccountId(Long accountId);

    /** Vrai si le compte a déjà un coureur dans la course (RG4, RG8 inc. 5). */
    boolean existsByRaceIdAndAccountId(Long raceId, Long accountId);

    /** Nombre de coureurs liés au compte, toutes courses et tous statuts (RG24 inc. 5). */
    long countByAccountId(Long accountId);
}
