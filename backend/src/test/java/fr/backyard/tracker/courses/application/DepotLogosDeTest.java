package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.Logo;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Double en mémoire du port DepotLogos (2.3). Un logo par Course au plus. Peut interdire la lecture des octets pour
 * vérifier qu'un cas d'usage de liste n'en a pas besoin.
 */
final class DepotLogosDeTest implements DepotLogos {

    private final Map<UUID, Logo> logos = new HashMap<>();
    private boolean lectureDesOctetsInterdite;
    int nombreDEcritures;

    void interdireLaLectureDesOctets() {
        lectureDesOctetsInterdite = true;
    }

    int nombreDeLogos() {
        return logos.size();
    }

    @Override
    public void enregistrer(UUID idCourse, Logo logo) {
        logos.put(idCourse, logo);
        nombreDEcritures++;
    }

    @Override
    public Optional<Logo> parIdCourse(UUID idCourse) {
        if (lectureDesOctetsInterdite) {
            throw new AssertionError("Les octets du logo ne doivent pas être lus");
        }
        return Optional.ofNullable(logos.get(idCourse));
    }

    @Override
    public void supprimer(UUID idCourse) {
        logos.remove(idCourse);
        nombreDEcritures++;
    }

    @Override
    public Map<UUID, String> empreintesParIdCourse() {
        Map<UUID, String> empreintes = new HashMap<>();
        logos.forEach((id, logo) -> empreintes.put(id, logo.empreinte()));
        return empreintes;
    }
}
