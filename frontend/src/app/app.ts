import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterOutlet } from '@angular/router';

import { Entete } from './entete/entete';

@Component({
  selector: 'app-root',
  imports: [Entete, RouterOutlet],
  template: '<app-entete /><router-outlet />',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {}
