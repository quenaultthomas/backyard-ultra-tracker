package fr.backyard.tracker.courses.infrastructure;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import fr.backyard.tracker.courses.application.ControlerAbandonsAEcheance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Incrément 7.2 (CA15, RG8) : le planificateur ne fait que déclencher le cas d'usage à chaque tick.
 * Signatures supposées : {@code PlanificateurAbandonAutomatique(ControlerAbandonsAEcheance)} (seule dépendance) et
 * tick {@code controlerLesCoursesEnCours()}.
 */
class PlanificateurAbandonAutomatiqueDelegationTest {

    private final ControlerAbandonsAEcheance casDUsage = mock(ControlerAbandonsAEcheance.class);
    private final PlanificateurAbandonAutomatique planificateur = new PlanificateurAbandonAutomatique(casDUsage);

    @Test
    @DisplayName("CA15 - à chaque tick, le planificateur appelle une fois ControlerAbandonsAEcheance.executer()")
    void doit_deleguer_chaque_tick_au_cas_d_usage() {
        planificateur.controlerLesCoursesEnCours();
        verify(casDUsage, times(1)).executer();

        planificateur.controlerLesCoursesEnCours();
        planificateur.controlerLesCoursesEnCours();

        verify(casDUsage, times(3)).executer();
        verifyNoMoreInteractions(casDUsage);
    }
}
