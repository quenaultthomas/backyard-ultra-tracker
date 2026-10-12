package fr.backyard.tracker.courses.exposition;

import fr.backyard.tracker.courses.domaine.DonneesCourseInvalidesException;
import fr.backyard.tracker.courses.domaine.JetonQr;
import fr.backyard.tracker.courses.domaine.ViolationValidation;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Corps de POST /api/benevole/courses/{courseId}/passages. Les champs sont lus sans conversion de type (texte,
 * nombre…) pour qu'une valeur de mauvais type soit une erreur de validation du champ et non un corps illisible.
 * Tout autre champ est ignoré. Le jeton n'apparaît jamais dans {@link #toString()}.
 */
public record PassageRequete(Object jetonQr, Object identifiantScan, Object ageDuScanMillisecondes) {

    static final int LONGUEUR_MAX_JETON = 200;
    static final long AGE_MAX_MILLISECONDES = 86_400_000L;

    /** Scan valide, prêt pour le cas d'usage. */
    record Scan(JetonQr jeton, UUID identifiantScan, long ageDuScanMillisecondes) {
    }

    @Override
    public String toString() {
        return "PassageRequete[jetonQr=masqué, identifiantScan=" + identifiantScan + "]";
    }

    /**
     * Contrôle de forme de tous les champs, dans l'ordre du contrat.
     *
     * @throws DonneesCourseInvalidesException au moins un champ invalide (une violation par champ)
     */
    Scan versScan() {
        List<ViolationValidation> violations = new ArrayList<>();
        Optional<JetonQr> jeton = jeton();
        Optional<UUID> identifiant = identifiant();
        Optional<Long> age = age();
        if (jeton.isEmpty()) {
            violations.add(new ViolationValidation("jetonQr", "JETON_INVALIDE",
                    "Le jeton est obligatoire (200 caractères au plus)."));
        }
        if (identifiant.isEmpty()) {
            violations.add(new ViolationValidation("identifiantScan", "IDENTIFIANT_SCAN_INVALIDE",
                    "L'identifiant de scan doit être un UUID."));
        }
        if (age.isEmpty()) {
            violations.add(new ViolationValidation("ageDuScanMillisecondes", "AGE_DU_SCAN_INVALIDE",
                    "L'âge du scan doit être compris entre 0 et 86400000 millisecondes."));
        }
        if (!violations.isEmpty()) {
            throw new DonneesCourseInvalidesException(violations);
        }
        return new Scan(jeton.orElseThrow(), identifiant.orElseThrow(), age.orElseThrow());
    }

    /**
     * Texte de 200 caractères au plus, non vide une fois nettoyé par le domaine ({@link JetonQr#depuisSaisie}) :
     * un refus de la normalisation devient une erreur de validation du champ.
     */
    private Optional<JetonQr> jeton() {
        if (!(jetonQr instanceof String saisie) || saisie.length() > LONGUEUR_MAX_JETON) {
            return Optional.empty();
        }
        try {
            return Optional.of(JetonQr.depuisSaisie(saisie));
        } catch (IllegalArgumentException saisieVide) {
            return Optional.empty();
        }
    }

    /** UUID sous sa forme canonique (36 caractères), casse indifférente. */
    private Optional<UUID> identifiant() {
        if (!(identifiantScan instanceof String texte)) {
            return Optional.empty();
        }
        return IdentifiantCourse.lire(texte).filter(uuid -> uuid.toString().equalsIgnoreCase(texte));
    }

    /** Entier de 0 à 24 h ; absent vaut 0 (scan en direct). */
    private Optional<Long> age() {
        if (ageDuScanMillisecondes == null) {
            return Optional.of(0L);
        }
        if (!(ageDuScanMillisecondes instanceof Integer || ageDuScanMillisecondes instanceof Long)) {
            return Optional.empty();
        }
        long valeur = ((Number) ageDuScanMillisecondes).longValue();
        return valeur >= 0 && valeur <= AGE_MAX_MILLISECONDES ? Optional.of(valeur) : Optional.empty();
    }
}
