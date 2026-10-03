import { ChangeDetectionStrategy, Component } from '@angular/core';

/** Accueil d'un bénévole : aucune Course à scanner tant que l'affectation n'existe pas. */
@Component({
  selector: 'app-accueil-benevole',
  templateUrl: './accueil-benevole.html',
  styleUrl: '../../partage/page-carte.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class AccueilBenevole {}
