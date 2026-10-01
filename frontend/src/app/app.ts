import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router, RouterLink, RouterOutlet } from '@angular/router';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { filter, map } from 'rxjs';
import { createAccountReturn, headerAccountZone } from './core/header-account-zone';
import { RunnerAuthState } from './infra/runner-auth-state';
import { ScanQueueService } from './infra/scan-queue.service';
import { RunnerSessionService } from './infra/runner-session.service';
import { SessionService } from './infra/session.service';

/**
 * Coquille de l'application : navigation, zone « compte » de l'en-tête (RG4 inc. 7, selon la seule connexion
 * coureur), bandeau de mise à jour (RG46). La file de scans démarre au lancement pour que les envois en attente
 * continuent quel que soit l'écran ouvert (RG22).
 */
@Component({
  selector: 'app-root',
  imports: [RouterOutlet, RouterLink],
  template: `
    <a class="skip-link" href="#contenu">Aller au contenu</a>
    <header class="app-header">
      <a routerLink="/" class="app-title">Backyard Ultra Tracker</a>
      <nav aria-label="Navigation principale">
        <a routerLink="/">Courses</a>
        <a routerLink="/compte">Mes inscriptions</a>
        <a routerLink="/scan">Scan</a>
      </nav>
      <div class="account-zone">
        @if (accountZone().connectedLabel; as label) {
          <span>{{ label }}</span>
        }
        @if (accountZone().loginLink) {
          <a routerLink="/connexion">Se connecter</a>
        }
        @if (accountZone().createAccountLink) {
          <a routerLink="/inscription" [queryParams]="{ retour: createAccountReturn() }">Créer un compte</a>
        }
      </div>
    </header>
    @if (updateReady()) {
      <div class="banner banner-info" role="status">
        <span>Nouvelle version disponible</span>
        <button type="button" class="button" (click)="applyUpdate()">Mettre à jour</button>
      </div>
    }
    <main id="contenu" tabindex="-1">
      <router-outlet />
    </main>
  `,
})
export class App {
  protected readonly updateReady = signal(false);
  private readonly swUpdate = inject(SwUpdate);
  private readonly router = inject(Router);
  private readonly runnerPseudo = inject(RunnerAuthState).pseudo;
  private readonly currentUrl = toSignal(
    this.router.events.pipe(
      filter((event): event is NavigationEnd => event instanceof NavigationEnd),
      map((event) => event.urlAfterRedirects),
    ),
    { initialValue: this.router.url },
  );
  protected readonly accountZone = computed(() => headerAccountZone(this.currentUrl(), this.runnerPseudo()));
  protected readonly createAccountReturn = computed(() => createAccountReturn(this.currentUrl()) ?? undefined);

  constructor() {
    inject(SessionService);
    inject(RunnerSessionService);
    void inject(ScanQueueService).start();
    if (this.swUpdate.isEnabled) {
      this.swUpdate.versionUpdates
        .pipe(filter((event): event is VersionReadyEvent => event.type === 'VERSION_READY'))
        .subscribe(() => this.updateReady.set(true));
    }
  }

  /** Mise à jour choisie par l'utilisateur, jamais forcée ; file de scans et identifiants mémorisés conservés. */
  protected async applyUpdate(): Promise<void> {
    await this.swUpdate.activateUpdate();
    document.location.reload();
  }
}
