package fr.backyard.tracker.courses.application;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/** Date du jour des Courses : celle de Paris, quel que soit le fuseau du serveur. Seul endroit de ce calcul. */
final class CalendrierDesCourses {

    private static final ZoneId FUSEAU_DES_COURSES = ZoneId.of("Europe/Paris");

    private CalendrierDesCourses() {
    }

    static LocalDate aujourdhui(Clock horloge) {
        return LocalDate.now(horloge.withZone(FUSEAU_DES_COURSES));
    }
}
