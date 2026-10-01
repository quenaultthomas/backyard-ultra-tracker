import { ChangeDetectionStrategy, Component } from '@angular/core';

import { Accueil } from './accueil/accueil';

@Component({
  selector: 'app-root',
  imports: [Accueil],
  template: '<app-accueil />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
