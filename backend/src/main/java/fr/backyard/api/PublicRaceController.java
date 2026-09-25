package fr.backyard.api;

import fr.backyard.api.dto.RaceBoardResponse;
import fr.backyard.api.dto.RaceResponse;
import fr.backyard.api.dto.RegistrationRequest;
import fr.backyard.api.dto.RegistrationResponse;
import fr.backyard.domain.Runner;
import fr.backyard.service.RaceBoardService;
import fr.backyard.service.RaceService;
import fr.backyard.service.RunnerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/** Consultation des courses et inscription, en accès libre (E1 à E4). */
@RestController
@RequestMapping("/api/public/races")
public class PublicRaceController {

    private final RaceService raceService;
    private final RunnerService runnerService;
    private final RaceBoardService raceBoardService;

    public PublicRaceController(RaceService raceService, RunnerService runnerService,
                                RaceBoardService raceBoardService) {
        this.raceService = raceService;
        this.runnerService = runnerService;
        this.raceBoardService = raceBoardService;
    }

    @GetMapping
    public List<RaceResponse> list() {
        return raceService.list().stream().map(RaceResponse::from).toList();
    }

    @GetMapping("/{raceId}")
    public RaceResponse get(@PathVariable Long raceId) {
        return RaceResponse.from(raceService.get(raceId));
    }

    @PostMapping("/{raceId}/registrations")
    public ResponseEntity<RegistrationResponse> register(@PathVariable Long raceId,
                                                         @Valid @RequestBody RegistrationRequest request) {
        Runner runner = runnerService.register(raceId, request.name());
        return ResponseEntity.created(URI.create("/api/public/runners/" + runner.getId()))
            .body(RegistrationResponse.from(runner));
    }

    @GetMapping("/{raceId}/board")
    public RaceBoardResponse board(@PathVariable Long raceId) {
        return RaceBoardResponse.from(raceBoardService.board(raceId));
    }
}
