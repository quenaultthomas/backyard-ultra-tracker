package fr.backyard.api;

import fr.backyard.api.dto.ScanRequest;
import fr.backyard.api.dto.ScanResponse;
import fr.backyard.service.PassageRecordingService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Enregistrement d'un passage scanné, 200 pour tout succès y compris le renvoi idempotent (E6). */
@RestController
@RequestMapping("/api/scan/passages")
public class ScanController {

    private final PassageRecordingService passageRecordingService;

    public ScanController(PassageRecordingService passageRecordingService) {
        this.passageRecordingService = passageRecordingService;
    }

    @PostMapping
    public ScanResponse scan(@Valid @RequestBody ScanRequest request) {
        return ScanResponse.from(passageRecordingService.recordScan(request.qrToken(), request.scannedAt()));
    }
}
