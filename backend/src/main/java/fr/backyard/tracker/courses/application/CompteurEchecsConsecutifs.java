package fr.backyard.tracker.courses.application;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Échecs consécutifs d'une tâche répétée chaque seconde, par clé, pour borner son journal (DEP-05) : un échec n'est
 * journalisé qu'aux rangs 1, 2, 4, 8… jusqu'à {@value #RANG_PALIER}, puis tous les {@value #RANG_PALIER} échecs
 * (environ toutes les 17 minutes à un tick par seconde). Une panne durable donne une dizaine de lignes par heure au
 * lieu de 3 600. Politique de journalisation purement technique, sans règle métier.
 */
final class CompteurEchecsConsecutifs<K> {

    static final int RANG_PALIER = 1024;

    private final Map<K, Integer> echecs = new ConcurrentHashMap<>();

    /** @return le rang de cet échec parmi les échecs consécutifs de la clé (1 pour le premier) */
    int enregistrerEchec(K cle) {
        return echecs.merge(cle, 1, Integer::sum);
    }

    /** @return le nombre d'échecs consécutifs interrompus par cette réussite (0 si la clé n'était pas en échec) */
    int enregistrerReussite(K cle) {
        Integer precedents = echecs.remove(cle);
        return precedents == null ? 0 : precedents;
    }

    /** Oublie les clés absentes de la collection (Course terminée ou supprimée pendant une panne). */
    void conserverSeulement(Collection<K> clesActives) {
        echecs.keySet().retainAll(clesActives);
    }

    static boolean doitJournaliser(int rang) {
        return rang <= RANG_PALIER ? Integer.bitCount(rang) == 1 : rang % RANG_PALIER == 0;
    }
}
