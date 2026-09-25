package fr.backyard.api;

import fr.backyard.api.dto.AdminRunnerResponse;
import fr.backyard.api.dto.RaceRequest;
import fr.backyard.api.dto.RaceResponse;
import fr.backyard.domain.Race;
import fr.backyard.service.RaceService;
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

import java.net.URI;
import java.util.List;

/** Gestion des courses par l'admin (E7 à E13). */
@RestController
@RequestMapping("/api/admin/races")
public class AdminRaceController {

    private final RaceService raceService;
    private final RunnerService runnerService;

    public AdminRaceController(RaceService raceService, RunnerService runnerService) {
        this.raceService = raceService;
        this.runnerService = runnerService;
    }

    @PostMapping
    public ResponseEntity<RaceResponse> create(@Valid @RequestBody RaceRequest request) {
        Race race = raceService.create(request.toCommand());
        return ResponseEntity.created(URI.create("/api/admin/races/" + race.getId()))
            .body(RaceResponse.from(race));
    }

    @GetMapping
    public List<RaceResponse> list() {
        return raceService.list().stream().map(RaceResponse::from).toList();
    }

    @GetMapping("/{raceId}")
    public RaceResponse get(@PathVariable Long raceId) {
        return RaceResponse.from(raceService.get(raceId));
    }

    @PutMapping("/{raceId}")
    public RaceResponse update(@PathVariable Long raceId, @Valid @RequestBody RaceRequest request) {
        return RaceResponse.from(raceService.update(raceId, request.toCommand()));
    }

    @DeleteMapping("/{raceId}")
    public ResponseEntity<Void> delete(@PathVariable Long raceId) {
        raceService.delete(raceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{raceId}/start")
    public RaceResponse start(@PathVariable Long raceId) {
        return RaceResponse.from(raceService.start(raceId));
    }

    @GetMapping("/{raceId}/runners")
    public List<AdminRunnerResponse> runners(@PathVariable Long raceId) {
        return runnerService.listByRace(raceId).stream().map(AdminRunnerResponse::from).toList();
    }
}
