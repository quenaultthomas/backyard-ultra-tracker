package fr.backyard.testsupport;

import fr.backyard.domain.Passage;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RaceRepository;
import fr.backyard.repository.RunnerRepository;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Repositories de l'increment 1 mockes avec Mockito, adosses a un petit etat en memoire
 * pour que les tests ne dependent pas du choix exact des methodes de lecture par le developpeur
 * (findByRaceId ou findByRaceIdAndStatus, findByRunnerId ou findByRunnerIdAndYardNumber).
 * Aucune base de donnees, aucun contexte Spring.
 * Toutes les ecritures (save / saveAll) sont journalisees pour les assertions "aucun save".
 */
public final class FakeRepositories {

    public final RaceRepository raceRepository = mock(RaceRepository.class);
    public final RunnerRepository runnerRepository = mock(RunnerRepository.class);
    public final PassageRepository passageRepository = mock(PassageRepository.class);

    private final List<Race> races = new ArrayList<>();
    private final List<Runner> runners = new ArrayList<>();
    private final List<Passage> passages = new ArrayList<>();

    private final List<Runner> savedRunners = new ArrayList<>();
    private final List<Passage> savedPassages = new ArrayList<>();
    private final List<Race> savedRaces = new ArrayList<>();

    private long nextPassageId = 90_000L;

    public FakeRepositories() {
        stubRaceRepository();
        stubRunnerRepository();
        stubPassageRepository();
    }

    // ----- alimentation de l'etat -----

    public FakeRepositories withRaces(Race... newRaces) {
        for (Race race : newRaces) {
            if (races.stream().noneMatch(r -> r == race)) {
                races.add(race);
            }
        }
        return this;
    }

    public FakeRepositories withRunners(Runner... newRunners) {
        for (Runner runner : newRunners) {
            if (runners.stream().noneMatch(r -> r == runner)) {
                runners.add(runner);
            }
        }
        return this;
    }

    /** Ajoute des passages existants (donnees de test), sans les compter comme des ecritures. */
    public FakeRepositories withPassages(Passage... newPassages) {
        for (Passage passage : newPassages) {
            store(passage);
        }
        return this;
    }

    // ----- lecture de l'etat -----

    public List<Passage> passagesOf(Runner runner) {
        return passages.stream()
            .filter(p -> Objects.equals(p.getRunner().getId(), runner.getId()))
            .sorted(Comparator.comparingInt(Passage::getYardNumber))
            .toList();
    }

    public List<Passage> allPassages() {
        return List.copyOf(passages);
    }

    public long runnerSaveCount(Runner runner) {
        return savedRunners.stream().filter(r -> r == runner).count();
    }

    public List<Runner> savedRunners() {
        return List.copyOf(savedRunners);
    }

    public List<Passage> savedPassages() {
        return List.copyOf(savedPassages);
    }

    public void clearWriteLog() {
        savedRunners.clear();
        savedPassages.clear();
        savedRaces.clear();
    }

    /** Aucun passage ni coureur ecrit (save/saveAll), aucun passage supprime. */
    public void assertNoWrite() {
        assertThat(savedPassages).as("passages sauvegardes").isEmpty();
        assertThat(savedRunners).as("coureurs sauvegardes").isEmpty();
        assertNoPassageDeletion();
    }

    public void assertNoPassageDeletion() {
        verify(passageRepository, never()).delete(any());
        verify(passageRepository, never()).deleteById(any());
        verify(passageRepository, never()).deleteAll(anyIterable());
        verify(passageRepository, never()).deleteAll();
        verify(passageRepository, never()).deleteAllInBatch(anyIterable());
        verify(passageRepository, never()).deleteAllByIdInBatch(anyIterable());
    }

    public List<Race> savedRaces() {
        return List.copyOf(savedRaces);
    }

    // ----- stubs -----

    private void stubRaceRepository() {
        when(raceRepository.findByStatus(any())).thenAnswer(inv -> {
            RaceStatus status = inv.getArgument(0);
            return new ArrayList<>(races.stream().filter(r -> r.getStatus() == status).toList());
        });
        when(raceRepository.findById(any())).thenAnswer(inv -> races.stream()
            .filter(r -> Objects.equals(r.getId(), inv.getArgument(0)))
            .findFirst());
        when(raceRepository.save(any(Race.class))).thenAnswer(inv -> {
            Race race = inv.getArgument(0);
            savedRaces.add(race);
            return race;
        });
    }

    private void stubRunnerRepository() {
        when(runnerRepository.findById(any())).thenAnswer(inv -> runners.stream()
            .filter(r -> Objects.equals(r.getId(), inv.getArgument(0)))
            .findFirst());
        when(runnerRepository.findByQrToken(any())).thenAnswer(inv -> runners.stream()
            .filter(r -> Objects.equals(r.getQrToken(), inv.getArgument(0)))
            .findFirst());
        when(runnerRepository.findByRaceId(any())).thenAnswer(inv -> runnersOfRace(inv.getArgument(0), null));
        when(runnerRepository.findByRaceIdAndStatus(any(), any()))
            .thenAnswer(inv -> runnersOfRace(inv.getArgument(0), inv.getArgument(1)));
        when(runnerRepository.save(any(Runner.class))).thenAnswer(inv -> {
            Runner runner = inv.getArgument(0);
            savedRunners.add(runner);
            return runner;
        });
        when(runnerRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<Runner> toSave = inv.getArgument(0);
            List<Runner> result = new ArrayList<>();
            toSave.forEach(r -> {
                savedRunners.add(r);
                result.add(r);
            });
            return result;
        });
    }

    private void stubPassageRepository() {
        when(passageRepository.findByRunnerId(any())).thenAnswer(inv -> new ArrayList<>(passages.stream()
            .filter(p -> Objects.equals(p.getRunner().getId(), inv.getArgument(0)))
            .toList()));
        when(passageRepository.findByRunnerIdAndYardNumber(any(), anyInt())).thenAnswer(inv -> {
            Long runnerId = inv.getArgument(0);
            int yard = inv.getArgument(1);
            return passages.stream()
                .filter(p -> Objects.equals(p.getRunner().getId(), runnerId) && p.getYardNumber() == yard)
                .findFirst();
        });
        when(passageRepository.save(any(Passage.class))).thenAnswer(inv -> {
            Passage passage = inv.getArgument(0);
            savedPassages.add(passage);
            return store(passage);
        });
        when(passageRepository.saveAll(anyIterable())).thenAnswer(inv -> {
            Iterable<Passage> toSave = inv.getArgument(0);
            List<Passage> result = new ArrayList<>();
            toSave.forEach(p -> {
                savedPassages.add(p);
                result.add(store(p));
            });
            return result;
        });
    }

    private List<Runner> runnersOfRace(Long raceId, RunnerStatus status) {
        return new ArrayList<>(runners.stream()
            .filter(r -> Objects.equals(r.getRace().getId(), raceId))
            .filter(r -> status == null || r.getStatus() == status)
            .toList());
    }

    private Passage store(Passage passage) {
        if (passage.getId() == null) {
            ReflectionTestUtils.setField(passage, "id", nextPassageId++);
            passages.add(passage);
        }
        return passage;
    }

    public Optional<Passage> passageOf(Runner runner, int yard) {
        return passagesOf(runner).stream().filter(p -> p.getYardNumber() == yard).findFirst();
    }
}
