package fr.backyard.api;

import fr.backyard.api.dto.RunnerDetailResponse;
import fr.backyard.service.RaceBoardService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Détail public d'un coureur, sans qr_token (E5). */
@RestController
@RequestMapping("/api/public/runners")
public class PublicRunnerController {

    private final RaceBoardService raceBoardService;

    public PublicRunnerController(RaceBoardService raceBoardService) {
        this.raceBoardService = raceBoardService;
    }

    @GetMapping("/{runnerId}")
    public RunnerDetailResponse get(@PathVariable Long runnerId) {
        return RunnerDetailResponse.from(raceBoardService.runnerDetail(runnerId));
    }
}
