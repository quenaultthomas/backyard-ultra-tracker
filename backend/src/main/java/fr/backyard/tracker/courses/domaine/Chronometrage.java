package fr.backyard.tracker.courses.domaine;

import java.time.Duration;
import java.time.Instant;

/**
 * Instantané du chronométrage d'une Course EN_COURS à un instant donné, calculé par {@link Course#chronometrage} et
 * jamais stocké. Les durées sont exactes à la milliseconde et jamais négatives.
 *
 * @param boucleCourante Boucle en cours, plafonnée au nombre maximum de Boucles
 * @param derniereBoucle la Boucle courante est la dernière autorisée
 * @param boucleMaxTerminee le temps de la dernière Boucle autorisée est écoulé
 * @param debutBoucleCourante départ de la Boucle courante
 * @param finBoucleCourante fin de la Boucle courante : prochain départ hors dernière Boucle
 * @param tempsAvantFinBoucle temps restant avant {@code finBoucleCourante}, zéro une fois dépassée
 * @param dureeEcoulee temps écoulé depuis le départ de la Boucle 1, sans plafond
 */
public record Chronometrage(int boucleCourante, boolean derniereBoucle, boolean boucleMaxTerminee,
                            Instant debutBoucleCourante, Instant finBoucleCourante, Duration tempsAvantFinBoucle,
                            Duration dureeEcoulee) {
}
