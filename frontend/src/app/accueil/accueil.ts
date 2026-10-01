import { ChangeDetectionStrategy, Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';

import { EtatApi } from '../sante/reponse-sante';
import { SanteApiService } from '../sante/sante-api.service';

const LIBELLES_ETAT_API: Record<EtatApi, string> = {
  VERIFICATION: 'API : vérification…',
  DISPONIBLE: 'API : disponible',
  INDISPONIBLE: 'API : indisponible',
};

@Component({
  selector: 'app-accueil',
  templateUrl: './accueil.html',
  styleUrl: './accueil.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Accueil {
  protected readonly etatApi = toSignal(inject(SanteApiService).surveillerEtat(), {
    initialValue: 'VERIFICATION' as EtatApi,
  });
  protected readonly libelleEtatApi = computed(() => LIBELLES_ETAT_API[this.etatApi()]);
}
