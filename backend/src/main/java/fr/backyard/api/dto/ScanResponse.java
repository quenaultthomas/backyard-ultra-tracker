package fr.backyard.api.dto;

import fr.backyard.domain.Passage;
import fr.backyard.domain.PassageSource;
import fr.backyard.domain.Runner;
import fr.backyard.domain.RunnerStatus;

import java.time.Instant;

/** Passage enregistré et état du coureur après le scan (RG20). Aucun qr_token. */
public record ScanResponse(
    Long passageId,
    Long runnerId,
    int bib,
    String runnerName,
    RunnerStatus runnerStatus,
    int yardNumber,
    PassageSource source,
    Instant scannedAt
) {

    public static ScanResponse from(Passage passage) {
        Runner runner = passage.getRunner();
        return new ScanResponse(passage.getId(), runner.getId(), runner.getBib(), runner.getName(),
            runner.getStatus(), passage.getYardNumber(), passage.getSource(), passage.getScannedAt());
    }
}
