import { Component, inject, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { SwUpdate, VersionReadyEvent } from '@angular/service-worker';
import { filter } from 'rxjs';
import { ScanQueueService } from './infra/scan-queue.service';
import { SessionService } from './infra/session.service';

/**
 * Coquille de l'application : navigation, bandeau de mise à jour (RG46). La file de scans démarre au lancement
 * pour que les envois en attente continuent quel que soit l'écran ouvert (RG22).
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
        <a routerLink="/scan">Scan</a>
        <a routerLink="/admin">Administration</a>
      </nav>
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

  constructor() {
    inject(SessionService);
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
