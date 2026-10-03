package fr.backyard.tracker.courses.domaine;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Port sortant de persistance des logos de Course : au plus un logo par Course. */
public interface DepotLogos {

    /** Ajoute le logo de la Course, ou remplace le précédent. */
    void enregistrer(UUID idCourse, Logo logo);

    Optional<Logo> parIdCourse(UUID idCourse);

    /** Sans effet si la Course n'a pas de logo. */
    void supprimer(UUID idCourse);

    /** Empreinte du logo de chaque Course qui en a un, sans lire les octets. */
    Map<UUID, String> empreintesParIdCourse();

    /** Empreinte du logo d'une Course, sans lire les octets ; vide si elle n'a pas de logo. */
    default Optional<String> empreinteParIdCourse(UUID idCourse) {
        return Optional.ofNullable(empreintesParIdCourse().get(idCourse));
    }
}
