package fr.backyard.service;

import fr.backyard.domain.DnfReason;
import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Race;
import fr.backyard.domain.RaceStatus;
import fr.backyard.domain.Runner;
import fr.backyard.domain.YardCalculator;
import fr.backyard.repository.PassageRepository;
import fr.backyard.repository.RunnerRepository;
import fr.backyard.service.exception.BusinessConflictException;
import fr.backyard.service.exception.InvalidInputException;
import fr.backyard.service.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

/**
 * Enregistrement d'un scan QR (RG12 à RG15), y compris la réactivation automatique
 * d'un coureur mis DNF par timeout dont le scan, fait à temps, arrive après la clôture (RG30 à RG32).
 */
@Service
public class PassageRecordingService {

    private final RunnerRepository runnerRepository;
    private final PassageRepository passageRepository;
    private final PassageLookup passageLookup;
    private final Clock clock;
    private final YardCalculator yardCalculator = new YardCalculator();

    public PassageRecordingService(RunnerRepository runnerRepository, PassageRepository passageRepository,
                                   Clock clock) {
        this.runnerRepository = runnerRepository;
        this.passageRepository = passageRepository;
        this.passageLookup = new PassageLookup(passageRepository);
        this.clock = clock;
    }

    /**
     * Enregistre le passage du coureur sur le yard contenant {@code scannedAt}. Les contrôles sont
     * appliqués dans l'ordre de RG12 ; un doublon identique est idempotent (RG15).
     */
    @Transactional
    public Passage recordScan(String qrToken, Instant scannedAt) {
        requireScannedAt(scannedAt);
        Runner runner = runnerRepository.findByQrToken(qrToken)
            .orElseThrow(() -> new ResourceNotFoundException("Aucun coureur pour le qr_token " + qrToken));
        Race race = runner.getRace();
        requireRunningRace(race, scannedAt);
        Instant now = Instant.now(clock);
        int scanYard = requireScanYard(race, scannedAt, now);

        Optional<Passage> existing = passageLookup.onYard(runner, scanYard);
        if (existing.isPresent()) {
            return idempotentDuplicate(runner, existing.get(), scannedAt);
        }
        boolean reactivation = requireRunnerCanScan(runner, race, scanYard, now);
        requirePassageOnPreviousYard(runner, scanYard);

        Passage passage = passageRepository.save(new Passage(runner, scanYard, PassageSource.SCAN, scannedAt));
        if (reactivation) {
            runner.reactivate();
            runnerRepository.save(runner);
        }
        return passage;
    }

    private static void requireScannedAt(Instant scannedAt) {
        if (scannedAt == null) {
            throw new InvalidInputException("L'instant du scan (scannedAt) est obligatoire");
        }
    }

    /** RG12.3 et RG31 : seule une course RUNNING accepte des scans. */
    private void requireRunningRace(Race race, Instant scannedAt) {
        if (race.isRunning()) {
            return;
        }
        String message = "Scan refusé : la course " + race.getId() + " est au statut " + race.getStatus()
            + " (attendu RUNNING)";
        if (race.getStatus() == RaceStatus.FINISHED) {
            message += " ; scan sur le yard " + yardCalculator.yardAt(race, scannedAt)
                + " reçu après la fin de course, à arbitrer par l'admin";
        }
        throw new BusinessConflictException(message);
    }

    /** RG12.4, RG12.5 et RG13 : yard du scan, attribué selon l'instant du scan et non de réception. */
    private int requireScanYard(Race race, Instant scannedAt, Instant now) {
        if (scannedAt.isAfter(now)) {
            throw new InvalidInputException("Scan dans le futur refusé : scannedAt " + scannedAt
                + " postérieur à l'heure serveur " + now);
        }
        int scanYard = yardCalculator.yardAt(race, scannedAt);
        if (scanYard == 0) {
            throw new InvalidInputException("Scan antérieur au départ refusé : scannedAt " + scannedAt
                + " avant le départ " + race.getStartedAt() + " de la course " + race.getId());
        }
        return scanYard;
    }

    /** RG15 : même instant, le passage existant est renvoyé ; sinon double scan refusé. */
    private static Passage idempotentDuplicate(Runner runner, Passage existing, Instant scannedAt) {
        if (scannedAt.equals(existing.getScannedAt())) {
            return existing;
        }
        throw new BusinessConflictException("Double scan refusé : le coureur " + runner.getId()
            + " a déjà un passage sur le yard " + existing.getYardNumber()
            + " (scanné à " + existing.getScannedAt() + ", nouveau scan à " + scannedAt + ")");
    }

    /**
     * RG12.7 : un coureur ACTIVE scanne normalement ; un coureur DNF n'est accepté qu'en mode
     * réactivation (RG30, RG32). Renvoie vrai si le scan doit réactiver le coureur.
     */
    private boolean requireRunnerCanScan(Runner runner, Race race, int scanYard, Instant now) {
        switch (runner.getStatus()) {
            case ACTIVE -> {
                return false;
            }
            case DNF -> {
                requireReactivable(runner, race, scanYard, now);
                return true;
            }
            default -> throw new BusinessConflictException("Scan refusé : le coureur " + runner.getId()
                + " est au statut " + runner.getStatus());
        }
    }

    /** RG30.2, RG30.3 puis RG32. */
    private void requireReactivable(Runner runner, Race race, int scanYard, Instant now) {
        if (runner.getDnfReason() != DnfReason.TIMEOUT || !Integer.valueOf(scanYard).equals(runner.getDnfYard())) {
            throw new BusinessConflictException("Scan refusé : le coureur " + runner.getId()
                + " est au statut DNF (raison " + runner.getDnfReason() + ", dnf_yard " + runner.getDnfYard()
                + ") et le scan sur le yard " + scanYard
                + " ne permet pas de réactivation automatique (réservée à un DNF TIMEOUT sur le yard scanné)");
        }
        int currentYard = yardCalculator.currentYard(race, now);
        if (scanYard != currentYard - 1) {
            throw new BusinessConflictException("Scan refusé : le scan du coureur " + runner.getId()
                + " porte sur le yard " + scanYard + " alors que le yard courant est " + currentYard
                + " ; seul le dernier yard clôturé permet une réactivation automatique,"
                + " une réintégration par l'admin est nécessaire");
        }
    }

    /** RG14 : un passage sur le yard k - 1 est obligatoire pour scanner le yard k. */
    private void requirePassageOnPreviousYard(Runner runner, int scanYard) {
        int previousYard = scanYard - 1;
        if (previousYard >= 1 && !passageLookup.hasValidPassageOnYard(runner, previousYard)) {
            throw new BusinessConflictException("Scan tardif refusé : le coureur " + runner.getId()
                + " n'a pas de passage valide sur le yard " + previousYard
                + " (scan attribué au yard " + scanYard + ")");
        }
    }
}
