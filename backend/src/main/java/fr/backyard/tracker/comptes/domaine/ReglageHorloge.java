package fr.backyard.tracker.comptes.domaine;

import java.time.Duration;

/**
 * Port de réglage de l'horloge applicative (profil e2e uniquement) : un décalage cumulé ajouté à l'heure système.
 * L'implémentation additionne sans autre règle ; les bornes sont appliquées par le cas d'usage qui l'appelle.
 */
public interface ReglageHorloge {

    /** Décalage cumulé courant, {@code PT0S} au départ. */
    Duration decalage();

    /** Ajoute {@code duree} au décalage de façon atomique et renvoie le décalage cumulé qui en résulte. */
    Duration avancer(Duration duree);

    /** Remet le décalage à {@code PT0S}. */
    void reinitialiser();
}
