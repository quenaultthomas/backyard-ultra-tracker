package fr.backyard.tracker.comptes.domaine;

import fr.backyard.tracker.partage.domaine.FormatImage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Sélection des fichiers du répertoire des photos à supprimer (correctif audit jalon 10 RG4, RG7) : seul endroit de
 * cette règle.
 * <ul>
 * <li>fichier de photo conforme ({@code <jeton>.<png|jpg|webp>}) : supprimé s'il est orphelin (jeton porté par aucun
 * Compte) et modifié depuis strictement plus de {@link #SEUIL_ANCIENNETE} ; la marge protège un envoi en cours ;</li>
 * <li>fichier temporaire ({@code <jeton>.<extension>.tmp}) : supprimé au-delà du même seuil, quel que soit l'état de la
 * base ;</li>
 * <li>tout autre nom, toute entrée non ordinaire et tout fichier porté dont l'extension diffère du format du Compte :
 * ignoré, jamais supprimé ;</li>
 * <li>garde-fou : plus de {@link #SEUIL_GARDE_FOU} orphelins à supprimer représentant plus de la moitié des fichiers
 * conformes suspendent la suppression des orphelins (base ou volume restauré à part) ; les temporaires restent
 * supprimés.</li>
 * </ul>
 */
public final class PolitiqueNettoyagePhotos {

    /** Âge au-delà duquel (strictement) un orphelin ou un temporaire est supprimé. */
    public static final Duration SEUIL_ANCIENNETE = Duration.ofHours(1);

    /** Nombre d'orphelins à partir duquel (strictement) le garde-fou peut suspendre la suppression. */
    public static final int SEUIL_GARDE_FOU = 10;

    /** Suffixe du fichier écrit avant son déplacement atomique sous son nom définitif. */
    public static final String SUFFIXE_TEMPORAIRE = ".tmp";

    private static final String EXTENSIONS = Arrays.stream(FormatImage.values()).map(FormatImage::extension)
            .collect(Collectors.joining("|"));
    private static final Pattern PHOTO = Pattern.compile("([0-9a-f]{32})\\.(" + EXTENSIONS + ")");
    private static final Pattern TEMPORAIRE = Pattern.compile("[0-9a-f]{32}\\.(" + EXTENSIONS + ")"
            + Pattern.quote(SUFFIXE_TEMPORAIRE));

    private enum Traitement { ORPHELIN_ANCIEN, TEMPORAIRE_ANCIEN, CONSERVE, IGNORE }

    private final Clock horloge;

    public PolitiqueNettoyagePhotos(Clock horloge) {
        this.horloge = horloge;
    }

    /** Jetons des fichiers de photo conformes et ordinaires, à vérifier en base en une seule fois. */
    public Set<String> jetonsConformes(Collection<FichierPhoto> fichiers) {
        Set<String> jetons = new LinkedHashSet<>();
        for (FichierPhoto fichier : fichiers) {
            Matcher photo = photo(fichier);
            if (photo != null) {
                jetons.add(photo.group(1));
            }
        }
        return jetons;
    }

    /**
     * @param jetonsPortes jeton de chaque Compte qui porte une photo parmi les fichiers, associé au format de celle-ci
     */
    public PlanNettoyage planifier(Collection<FichierPhoto> fichiers, Map<String, FormatImage> jetonsPortes) {
        Instant limite = horloge.instant().minus(SEUIL_ANCIENNETE);
        List<String> orphelins = new ArrayList<>();
        List<String> temporaires = new ArrayList<>();
        int ignores = 0;
        for (FichierPhoto fichier : fichiers) {
            switch (traitement(fichier, jetonsPortes, limite)) {
                case ORPHELIN_ANCIEN -> orphelins.add(fichier.nom());
                case TEMPORAIRE_ANCIEN -> temporaires.add(fichier.nom());
                case IGNORE -> ignores++;
                case CONSERVE -> { }
            }
        }
        int conformes = nombreConformes(fichiers);
        boolean suspendu = orphelins.size() > SEUIL_GARDE_FOU && orphelins.size() * 2 > conformes;
        return new PlanNettoyage(suspendu ? List.of() : orphelins, temporaires, ignores, suspendu, orphelins.size(),
                conformes);
    }

    private static Traitement traitement(FichierPhoto fichier, Map<String, FormatImage> jetonsPortes, Instant limite) {
        if (!fichier.ordinaire()) {
            return Traitement.IGNORE;
        }
        boolean ancien = fichier.modifieLe().isBefore(limite);
        Matcher photo = PHOTO.matcher(fichier.nom());
        if (photo.matches()) {
            return traitementPhoto(jetonsPortes.get(photo.group(1)), photo.group(2), ancien);
        }
        if (TEMPORAIRE.matcher(fichier.nom()).matches()) {
            return ancien ? Traitement.TEMPORAIRE_ANCIEN : Traitement.CONSERVE;
        }
        return Traitement.IGNORE;
    }

    /** Un fichier porté n'est jamais supprimé ; une extension différente du format du Compte est une incohérence. */
    private static Traitement traitementPhoto(FormatImage formatPorte, String extension, boolean ancien) {
        if (formatPorte != null) {
            return formatPorte.extension().equals(extension) ? Traitement.CONSERVE : Traitement.IGNORE;
        }
        return ancien ? Traitement.ORPHELIN_ANCIEN : Traitement.CONSERVE;
    }

    private static int nombreConformes(Collection<FichierPhoto> fichiers) {
        return (int) fichiers.stream().filter(fichier -> photo(fichier) != null).count();
    }

    /** Correspondance du nom d'un fichier de photo conforme et ordinaire, null sinon. */
    private static Matcher photo(FichierPhoto fichier) {
        if (!fichier.ordinaire()) {
            return null;
        }
        Matcher photo = PHOTO.matcher(fichier.nom());
        return photo.matches() ? photo : null;
    }
}
