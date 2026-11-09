package fr.backyard.tracker.courses.application;

import fr.backyard.tracker.courses.application.ListerInscritsCourse.Inscrit;
import fr.backyard.tracker.courses.domaine.AnnuairePhotos;
import fr.backyard.tracker.courses.domaine.AnnuairePseudos;
import fr.backyard.tracker.courses.domaine.Chronometrage;
import fr.backyard.tracker.courses.domaine.Course;
import fr.backyard.tracker.courses.domaine.CourseIntrouvableException;
import fr.backyard.tracker.courses.domaine.DepotCourses;
import fr.backyard.tracker.courses.domaine.DepotInscriptions;
import fr.backyard.tracker.courses.domaine.DepotLogos;
import fr.backyard.tracker.courses.domaine.DepotPassages;
import fr.backyard.tracker.courses.domaine.Inscription;
import fr.backyard.tracker.courses.domaine.MotifAbandon;
import fr.backyard.tracker.courses.domaine.Passage;
import fr.backyard.tracker.courses.domaine.StatistiquesCourse;
import fr.backyard.tracker.courses.domaine.StatutInscription;
import fr.backyard.tracker.partage.domaine.AdressePhoto;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Suivi public d'une Course : la Course, son chronométrage à l'instant de l'horloge (lu une seule fois) et ses
 * coureurs répartis selon le statut enregistré de leur Inscription (jamais recalculé), et ses statistiques calculées
 * par le domaine ({@link Course#statistiques}). Au plus six lectures : la Course d'abord, l'empreinte de son logo
 * (sans les octets), puis ses Inscriptions,
 * les pseudos et les Passages de toutes ses Inscriptions en une seule lecture groupée (pseudos et Passages omis sans
 * Inscription), et les photos des seuls coureurs en course et vainqueurs en une seule lecture groupée (omise s'il n'y en
 * a aucun, 10.2 RG2) ; rien d'autre n'est lu tant que la Course n'expose pas ses coureurs (EN_PREPARATION). Lire la Course
 * avant les Inscriptions garantit qu'une Course TERMINEE est vue avec ses statuts finaux (écrits dans la même
 * transaction). Lecture seule, aucun journal en fonctionnement normal (lecture fréquente et publique).
 *
 * <p>Micro-cache (7.2) : les lectures (Course, empreinte du logo, Inscriptions, pseudos, photos, Passages) sont conservées par Course pendant
 * {@link #DUREE_DU_CACHE} ; chronométrage, statistiques et {@code heureServeur} sont recalculés à chaque appel à
 * l'instant de l'horloge. Une seule lecture à la fois par Course : les appels concurrents sur une entrée expirée
 * attendent ce chargement et partagent son résultat. Une erreur de lecture n'est jamais mise en cache ; une Course
 * inconnue ne crée aucune entrée. Sans transaction englobante : un appel servi par le cache n'emprunte aucune
 * connexion, chaque lecture d'un chargement ouvre la sienne, la Course toujours lue en premier.
 */
@Service
public class ConsulterSuivi {

    /** Coureur en course ou vainqueur : dossard, pseudo et adresse relative de sa photo (null sans photo, 10.2 RG1). */
    public record CoureurSuivi(int dossard, String pseudo, String photoUrl) {

        /** Dossard uniquement : jamais de pseudo ni d'adresse de photo dans les journaux. */
        @Override
        public String toString() {
            return "CoureurSuivi[dossard=" + dossard + "]";
        }
    }

    /** Abandon : Boucle et motif vides pour un abandon antérieur enregistré sans eux. */
    public record AbandonSuivi(int dossard, String pseudo, Optional<Integer> boucleAbandon,
                               Optional<MotifAbandon> motifAbandon) {

        /** Dossard uniquement : jamais de pseudo dans les journaux. */
        @Override
        public String toString() {
            return "AbandonSuivi[dossard=" + dossard + "]";
        }
    }

    /**
     * Instantané : chronométrage vide hors EN_COURS ou sans heure de départ ; en course et vainqueurs par dossard,
     * abandons selon {@link Inscription#ORDRE_DES_ABANDONS} ; statistiques de la Course à {@code heureServeur} ;
     * empreinte du logo vide sans logo.
     */
    public record Suivi(Course course, Optional<String> empreinteLogo, Instant heureServeur, Optional<Chronometrage> chronometrage,
                        List<CoureurSuivi> coureursEnCourse, List<AbandonSuivi> abandons,
                        List<CoureurSuivi> vainqueurs, StatistiquesCourse statistiques) {

        public Suivi {
            coureursEnCourse = List.copyOf(coureursEnCourse);
            abandons = List.copyOf(abandons);
            vainqueurs = List.copyOf(vainqueurs);
        }

        /** Identifiant de la Course uniquement. */
        @Override
        public String toString() {
            return "Suivi[course=" + course.id() + "]";
        }
    }

    /** Durée de réutilisation des lectures d'une Course : un suivi servi a moins d'une seconde de retard (RG2). */
    public static final Duration DUREE_DU_CACHE = Duration.ofSeconds(1);

    /** Statuts dont les coureurs sont affichés avec leur photo : jamais les abandons (10.2 RG1, RG2). */
    private static final Set<StatutInscription> AVEC_PHOTO = Set.of(StatutInscription.EN_COURSE,
            StatutInscription.VAINQUEUR);

    /**
     * Lectures d'une Course à l'instant {@code chargeeLe} ; Inscriptions, inscrits, photos et Passages vides hors
     * exposition. {@code photosParInscription} : adresse de la photo par identifiant d'Inscription, absente sans photo.
     */
    private record Lectures(Instant chargeeLe, Course course, Optional<String> empreinteLogo,
                            List<Inscription> inscriptions, List<Inscrit> parDossard,
                            Map<UUID, String> photosParInscription, List<Passage> passages) {

        private Lectures {
            inscriptions = List.copyOf(inscriptions);
            parDossard = List.copyOf(parDossard);
            photosParInscription = Map.copyOf(photosParInscription);
            passages = List.copyOf(passages);
        }

        /** Réutilisable si {@code 0 <= maintenant - chargeeLe < DUREE_DU_CACHE} (horloge reculée : à relire). */
        boolean valableA(Instant maintenant) {
            return !maintenant.isBefore(chargeeLe) && maintenant.isBefore(chargeeLe.plus(DUREE_DU_CACHE));
        }
    }

    private final Map<UUID, Lectures> cache = new ConcurrentHashMap<>();
    private final DepotCourses depotCourses;
    private final DepotInscriptions depotInscriptions;
    private final AnnuairePseudos annuairePseudos;
    private final AnnuairePhotos annuairePhotos;
    private final DepotPassages depotPassages;
    private final DepotLogos depotLogos;
    private final Clock horloge;

    public ConsulterSuivi(DepotCourses depotCourses, DepotInscriptions depotInscriptions,
                          AnnuairePseudos annuairePseudos, AnnuairePhotos annuairePhotos,
                          DepotPassages depotPassages, DepotLogos depotLogos, Clock horloge) {
        this.depotCourses = depotCourses;
        this.depotInscriptions = depotInscriptions;
        this.annuairePseudos = annuairePseudos;
        this.annuairePhotos = annuairePhotos;
        this.depotPassages = depotPassages;
        this.depotLogos = depotLogos;
        this.horloge = horloge;
    }

    /** @throws CourseIntrouvableException aucune Course pour cet identifiant */
    public Suivi executer(UUID courseId) {
        Instant maintenant = horloge.instant();
        Lectures lectures = lecturesA(courseId, maintenant);
        Course course = lectures.course();
        Optional<Chronometrage> chronometrage = course.chronometrage(maintenant);
        return new Suivi(course, lectures.empreinteLogo(), maintenant, chronometrage,
                coureurs(lectures, StatutInscription.EN_COURSE),
                abandons(lectures.inscriptions(), lectures.parDossard()),
                coureurs(lectures, StatutInscription.VAINQUEUR),
                course.statistiques(maintenant, lectures.passages()));
    }

    /** Entrée valable sans verrou, sinon chargement exclusif pour cette Course ; Course absente : aucune entrée. */
    private Lectures lecturesA(UUID courseId, Instant maintenant) {
        Lectures enCache = cache.get(courseId);
        if (enCache != null && enCache.valableA(maintenant)) {
            return enCache;
        }
        Lectures lectures = cache.compute(courseId, (id, precedentes) ->
                precedentes != null && precedentes.valableA(maintenant) ? precedentes : charger(id, maintenant));
        if (lectures == null) {
            throw new CourseIntrouvableException();
        }
        return lectures;
    }

    /** Null si la Course n'existe pas (l'entrée est alors retirée) ; rien d'autre n'est lu hors exposition. */
    private Lectures charger(UUID courseId, Instant maintenant) {
        Optional<Course> course = depotCourses.parId(courseId);
        if (course.isEmpty()) {
            return null;
        }
        Optional<String> empreinteLogo = depotLogos.empreinteParIdCourse(courseId);
        if (!course.get().exposeSesCoureursAuPublic()) {
            return new Lectures(maintenant, course.get(), empreinteLogo, List.of(), List.of(), Map.of(), List.of());
        }
        List<Inscription> inscriptions = depotInscriptions.parCourse(courseId);
        return new Lectures(maintenant, course.get(), empreinteLogo, inscriptions,
                ListerInscritsCourse.inscritsParDossard(inscriptions, annuairePseudos), photosDe(inscriptions),
                passagesDe(inscriptions));
    }

    /**
     * Une seule lecture groupée des photos des coureurs en course et vainqueurs, omise s'il n'y en a aucun ; l'adresse
     * publique est calculée par {@link AdressePhoto}, seul détenteur du préfixe.
     */
    private Map<UUID, String> photosDe(List<Inscription> inscriptions) {
        List<Inscription> avecPhoto = inscriptions.stream()
                .filter(inscription -> AVEC_PHOTO.contains(inscription.statut())).toList();
        if (avecPhoto.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> jetons = annuairePhotos.jetonsPhotoDe(
                avecPhoto.stream().map(Inscription::compteId).distinct().toList());
        return avecPhoto.stream().filter(inscription -> jetons.containsKey(inscription.compteId()))
                .collect(Collectors.toUnmodifiableMap(Inscription::id,
                        inscription -> AdressePhoto.de(jetons.get(inscription.compteId()))));
    }

    /** Une seule lecture groupée des Passages de toutes les Inscriptions, omise sans Inscription. */
    private List<Passage> passagesDe(List<Inscription> inscriptions) {
        if (inscriptions.isEmpty()) {
            return List.of();
        }
        return depotPassages.parInscriptions(inscriptions.stream().map(Inscription::id).toList());
    }

    private static List<CoureurSuivi> coureurs(Lectures lectures, StatutInscription statut) {
        return lectures.parDossard().stream().filter(inscrit -> inscrit.statut() == statut)
                .map(inscrit -> new CoureurSuivi(inscrit.dossard(), inscrit.pseudo(),
                        lectures.photosParInscription().get(inscrit.inscriptionId())))
                .toList();
    }

    private static List<AbandonSuivi> abandons(List<Inscription> inscriptions, List<Inscrit> inscrits) {
        Map<UUID, String> pseudos = inscrits.stream()
                .collect(Collectors.toMap(Inscrit::inscriptionId, Inscrit::pseudo));
        return inscriptions.stream().filter(inscription -> inscription.statut() == StatutInscription.ABANDON)
                .sorted(Inscription.ORDRE_DES_ABANDONS)
                .map(abandon(pseudos)).toList();
    }

    private static Function<Inscription, AbandonSuivi> abandon(Map<UUID, String> pseudos) {
        return inscription -> new AbandonSuivi(inscription.dossard(), pseudos.get(inscription.id()),
                inscription.boucleAbandon(), inscription.motifAbandon());
    }
}
