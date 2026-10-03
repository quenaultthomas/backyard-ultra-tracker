package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.Logo;
import fr.backyard.tracker.courses.domaine.LogoIntrouvableException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lecture publique du logo d'une Course, quel que soit son statut. */
@Service
public class LireLogo {

    private final DepotLogos depotLogos;

    public LireLogo(DepotLogos depotLogos) {
        this.depotLogos = depotLogos;
    }

    /** @throws LogoIntrouvableException Course sans logo ou inconnue, sans distinction */
    @Transactional(readOnly = true)
    public Logo executer(UUID idCourse) {
        return depotLogos.parIdCourse(idCourse).orElseThrow(LogoIntrouvableException::new);
    }
}
