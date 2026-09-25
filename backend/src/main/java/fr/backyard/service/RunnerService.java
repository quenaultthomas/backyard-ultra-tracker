package fr.backyard.service;

import fr.backyard.domain.Race;
import fr.backyard.domain.Runner;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Gestion des coureurs : inscription publique, lecture, modification, suppression (RG15 à RG19 inc. 3).
 */
@Service
public class RunnerService {

    private final RaceRepository raceRepository;
    private final RunnerRepository runnerRepository;
    private final PassageRepository passageRepository;
    private final QrTokenGenerator qrTokenGenerator;

    public RunnerService(RaceRepository raceRepository, RunnerRepository runnerRepository,
                         PassageRepository passageRepository, QrTokenGenerator qrTokenGenerator) {
        this.raceRepository = raceRepository;
        this.runnerRepository = runnerRepository;
        this.passageRepository = passageRepository;
        this.qrTokenGenerator = qrTokenGenerator;
    }

    /** RG15 : inscription sur une course aux inscriptions ouvertes, dossard = plus grand + 1 (PO8). */
    @Transactional
    public Runner register(Long raceId, String name) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        if (!race.isRegistrationOpen()) {
            throw new BusinessConflictException("Inscriptions fermées : course " + raceId
                + " au statut " + race.getStatus());
        }
        int bib = nextBib(runnerRepository.findByRaceId(raceId));
        return runnerRepository.save(new Runner(race, bib, name, qrTokenGenerator.generate()));
    }

    /** RG17 : coureurs d'une course existante, par dossard croissant. */
    @Transactional(readOnly = true)
    public List<Runner> listByRace(Long raceId) {
        BusinessGuards.requireRace(raceRepository, raceId);
        return runnerRepository.findByRaceId(raceId).stream().sorted(Runner.BIB_ORDER).toList();
    }

    @Transactional(readOnly = true)
    public Runner get(Long runnerId) {
        return BusinessGuards.requireRunner(runnerRepository, runnerId);
    }

    /**
     * RG18 : nom toujours modifiable ; dossard modifiable uniquement en SETUP (PO11) et s'il est libre
     * dans la course.
     */
    @Transactional
    public Runner update(Long runnerId, int bib, String name) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        if (bib != runner.getBib()) {
            requireBibChangeAllowed(runner, bib);
            runner.setBib(bib);
        }
        runner.setName(name);
        return runnerRepository.save(runner);
    }

    /** RG19 : suppression d'un coureur sans passage, course SETUP uniquement. */
    @Transactional
    public void delete(Long runnerId) {
        Runner runner = BusinessGuards.requireRunner(runnerRepository, runnerId);
        BusinessGuards.requireSetupRace(runner.getRace(),
            "supprimer le coureur " + runnerId + " (un abandon en course passe par le DNF manuel)");
        if (passageRepository.existsByRunnerId(runnerId)) {
            throw new BusinessConflictException("Impossible de supprimer le coureur " + runnerId
                + " : il a des passages, qui sont immuables");
        }
        runnerRepository.delete(runner);
    }

    private void requireBibChangeAllowed(Runner runner, int bib) {
        Race race = runner.getRace();
        BusinessGuards.requireSetupRace(race, "modifier le dossard du coureur " + runner.getId());
        if (runnerRepository.existsByRaceIdAndBibAndIdNot(race.getId(), bib, runner.getId())) {
            throw new BusinessConflictException("Le dossard " + bib + " est déjà attribué dans la course "
                + race.getId());
        }
    }

    private static int nextBib(List<Runner> runnersOfRace) {
        return runnersOfRace.stream().mapToInt(Runner::getBib).max().orElse(0) + 1;
    }
}
