package fr.backyard.service;

import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Gestion des courses : création, lecture, modification, suppression, démarrage (RG9 à RG13 inc. 3).
 */
@Service
public class RaceService {

    /** Ordre de la liste des courses (RG10 inc. 3) : date de course puis id croissants. */
    private static final Sort RACE_LIST_ORDER = Sort.by("raceDate").ascending().and(Sort.by("id").ascending());

    private final RaceRepository raceRepository;
    private final RunnerRepository runnerRepository;
    private final Clock clock;

    public RaceService(RaceRepository raceRepository, RunnerRepository runnerRepository, Clock clock) {
        this.raceRepository = raceRepository;
        this.runnerRepository = runnerRepository;
        this.clock = clock;
    }

    /** RG9 : nom unique, course créée au statut SETUP sans heure de départ. */
    @Transactional
    public Race create(RaceCommand command) {
        if (raceRepository.existsByName(command.name())) {
            throw new BusinessConflictException("Une course porte déjà le nom « " + command.name() + " »");
        }
        Race race = new Race(command.name(), command.raceDate(), command.loopDistance(), command.loopDuration(),
            command.loopElevation());
        return raceRepository.save(race);
    }

    @Transactional(readOnly = true)
    public List<Race> list() {
        return raceRepository.findAll(RACE_LIST_ORDER);
    }

    @Transactional(readOnly = true)
    public Race get(Long raceId) {
        return BusinessGuards.requireRace(raceRepository, raceId);
    }

    /**
     * RG11 : remplacement complet. Nom et date toujours modifiables ; paramètres de boucle figés
     * hors SETUP (renvoyer les mêmes valeurs est accepté).
     */
    @Transactional
    public Race update(Long raceId, RaceCommand command) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        if (raceRepository.existsByNameAndIdNot(command.name(), raceId)) {
            throw new BusinessConflictException("Une autre course porte déjà le nom « " + command.name() + " »");
        }
        boolean loopParametersChanged = race.hasDifferentLoopParameters(command.loopDistance(),
            command.loopDuration(), command.loopElevation());
        if (loopParametersChanged && race.getStatus() != RaceStatus.SETUP) {
            throw new BusinessConflictException("Paramètres de boucle non modifiables : course " + raceId
                + " au statut " + race.getStatus());
        }
        race.setName(command.name());
        race.setRaceDate(command.raceDate());
        race.setLoopDistance(command.loopDistance());
        race.setLoopDuration(command.loopDuration());
        race.setLoopElevation(command.loopElevation());
        return raceRepository.save(race);
    }

    /** RG12 : seule une course SETUP est supprimée, avec ses coureurs (PO10). */
    @Transactional
    public void delete(Long raceId) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        BusinessGuards.requireSetupRace(race, "supprimer la course " + raceId);
        List<Runner> runners = runnerRepository.findByRaceId(raceId);
        runnerRepository.deleteAll(runners);
        raceRepository.delete(race);
    }

    /** RG13 : démarrage SETUP vers RUNNING à l'heure du serveur. */
    @Transactional
    public Race start(Long raceId) {
        Race race = BusinessGuards.requireRace(raceRepository, raceId);
        BusinessGuards.requireSetupRace(race, "démarrer la course " + raceId);
        race.start(Instant.now(clock));
        return raceRepository.save(race);
    }

}
