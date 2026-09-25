package fr.backyard.api;

import fr.backyard.api.dto.AdminRunnerResponse;
import fr.backyard.api.dto.DnfRequest;
import fr.backyard.api.dto.PassageResponse;
import fr.backyard.api.dto.ReintegrationResponse;
import fr.backyard.api.dto.RunnerUpdateRequest;
import fr.backyard.domain.Passage;
import fr.backyard.service.ManualDnfService;
import fr.backyard.service.RaceBoardService;
import fr.backyard.service.ReintegrationService;
import fr.backyard.service.RunnerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Gestion des coureurs et actions de course de l'admin : DNF manuel, réintégration (E14 à E18). */
@RestController
@RequestMapping("/api/admin/runners")
public class AdminRunnerController {

    private final RunnerService runnerService;
    private final ManualDnfService manualDnfService;
    private final ReintegrationService reintegrationService;
    private final RaceBoardService raceBoardService;

    public AdminRunnerController(RunnerService runnerService, ManualDnfService manualDnfService,
                                 ReintegrationService reintegrationService, RaceBoardService raceBoardService) {
        this.runnerService = runnerService;
        this.manualDnfService = manualDnfService;
        this.reintegrationService = reintegrationService;
        this.raceBoardService = raceBoardService;
    }

    @GetMapping("/{runnerId}")
    public AdminRunnerResponse get(@PathVariable Long runnerId) {
        return AdminRunnerResponse.from(runnerService.get(runnerId));
    }

    @PutMapping("/{runnerId}")
    public AdminRunnerResponse update(@PathVariable Long runnerId, @Valid @RequestBody RunnerUpdateRequest request) {
        return AdminRunnerResponse.from(runnerService.update(runnerId, request.bib(), request.name()));
    }

    @DeleteMapping("/{runnerId}")
    public ResponseEntity<Void> delete(@PathVariable Long runnerId) {
        runnerService.delete(runnerId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{runnerId}/dnf")
    public AdminRunnerResponse declareDnf(@PathVariable Long runnerId, @RequestBody DnfRequest request) {
        return AdminRunnerResponse.from(manualDnfService.declareDnf(runnerId, request.reason()));
    }

    @PostMapping("/{runnerId}/reintegration")
    public ReintegrationResponse reintegrate(@PathVariable Long runnerId) {
        List<Passage> recreated = reintegrationService.reintegrate(runnerId);
        AdminRunnerResponse runner = AdminRunnerResponse.from(runnerService.get(runnerId));
        List<PassageResponse> recreatedPassages = raceBoardService.describePassages(runnerId, recreated).stream()
            .map(PassageResponse::from)
            .toList();
        return new ReintegrationResponse(runner, recreatedPassages);
    }
}
