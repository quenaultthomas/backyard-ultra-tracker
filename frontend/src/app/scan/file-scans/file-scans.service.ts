import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, firstValueFrom, timeout } from 'rxjs';

import { RefusAccesService } from '../../comptes/refus-acces.service';
import { estBenevole } from '../../comptes/roles';
import { SessionService } from '../../comptes/session.service';
import { CsrfService } from '../../partage/csrf.service';
import { BenevoleApiService } from '../benevole-api.service';
import { PassageReponse } from '../passage';
import { RefusScan } from '../refus-scan';
import { IssueEnvoi, REFUS_TROP_ANCIEN, attenteApresPanne, classerErreur } from './issue-envoi';
import {
  ScanEnFile,
  ageDuScan,
  creerScanEnFile,
  estDeCetteSessionDePage,
  estTropAncien,
} from './scan-en-file';
import { StockageScans } from './stockage-scans';

/** Au-delà, la tentative est une panne (le serveur a pu traiter : le rejeu est sans effet). */
const DELAI_REQUETE_MILLISECONDES = 10_000;
/** Le message de session expirée reste lisible avant la redirection vers la connexion. */
const DELAI_AVANT_RECONNEXION_MILLISECONDES = 2_000;

type IssueScan =
  | { type: 'succes'; passage: PassageReponse }
  | { type: 'refus'; refus: RefusScan; enDirect: boolean }
  | { type: 'attente' };

/** Ce qui arrive à un scan de la file, pour l'écran de sa Course. */
export type EvenementScan = { identifiantScan: string; courseId: string } & IssueScan;

/** Scan mis en file ; `ecrit` se résout quand l'entrée est durablement écrite (jamais rejetée). */
export interface ScanAjoute {
  scan: ScanEnFile;
  ecrit: Promise<void>;
}

interface Pause {
  cause: 'SESSION_EXPIREE' | 'ACCES_REFUSE';
  enAttente: number;
}

/**
 * File locale des scans du bénévole connecté : chaque scan est écrit avant d'être envoyé, puis
 * envoyé un à la fois dans l'ordre de création, avec nouvelles tentatives après une panne.
 * Le serveur décide de tout (Boucle, refus) ; la file ne fait que classer ses réponses.
 * Le cycle d'envoi tourne sur toute page de l'application.
 */
@Injectable({ providedIn: 'root' })
export class FileScansService {
  private readonly stockage = inject(StockageScans);
  private readonly benevoleApi = inject(BenevoleApiService);
  private readonly csrf = inject(CsrfService);
  private readonly session = inject(SessionService);
  private readonly refusAcces = inject(RefusAccesService);
  private readonly router = inject(Router);
  private readonly document = inject(DOCUMENT);

  /** Entrées du Compte connecté, dans l'ordre d'envoi. */
  private readonly scans = signal<ScanEnFile[]>([]);
  /** Entrées créées dont l'écriture locale n'est pas terminée : pas encore comptées. */
  private readonly nonEcrits = signal<ReadonlySet<string>>(new Set());
  private readonly enLigne = signal(navigator.onLine);
  private readonly pause = signal<Pause | null>(null);
  private readonly envoyes = signal<number | null>(null);
  private readonly evenements = new Subject<EvenementScan>();

  readonly horsLigne = computed(() => !this.enLigne());
  readonly stockagePersistant = this.stockage.persistant;
  /** Nombre de scans envoyés quand la file vient de se vider après une attente. */
  readonly scansEnvoyes = this.envoyes.asReadonly();
  /** Nombre de scans en attente quand la session a expiré pendant un envoi. */
  readonly sessionExpiree = computed(() => {
    const pause = this.pause();
    return pause?.cause === 'SESSION_EXPIREE' ? pause.enAttente : null;
  });
  readonly evenements$: Observable<EvenementScan> = this.evenements.asObservable();

  private compteId: string | null = null;
  private ecritures: Promise<void> = Promise.resolve();
  private purgeFaite = false;
  private cycleEnCours = false;
  private relanceDemandee = false;
  private pannesConsecutives = 0;
  private minuterie: ReturnType<typeof setTimeout> | undefined;
  private redirection: ReturnType<typeof setTimeout> | undefined;
  private attenteConstatee = false;
  private succesDepuisAttente = 0;

  constructor() {
    const fenetre = this.document.defaultView;
    fenetre?.addEventListener('online', () => {
      this.enLigne.set(true);
      this.relancer();
    });
    fenetre?.addEventListener('offline', () => this.enLigne.set(false));
    this.document.addEventListener('visibilitychange', () => {
      if (this.document.visibilityState === 'visible') {
        this.relancer();
      }
    });
    effect(() => {
      const compte = this.session.compte();
      untracked(() => this.changerDeCompte(compte && estBenevole(compte.role) ? compte.id : null));
    });
  }

  /**
   * Entrées en attente du Compte connecté pour une Course, une fois écrites sur l'appareil (la
   * première tentative n'en est pas).
   */
  enAttente(courseId: string): number {
    const nonEcrits = this.nonEcrits();
    return this.scans().filter(
      (scan) =>
        scan.courseId === courseId &&
        scan.etat === 'EN_ATTENTE' &&
        !nonEcrits.has(scan.identifiantScan),
    ).length;
  }

  /**
   * Écrit le scan dans la file, puis lance l'envoi si la file était vide. Envoyé en direct si
   * aucune entrée n'est en attente devant lui (en ligne). `null` sans Compte.
   */
  ajouter(courseId: string, jetonQr: string): ScanAjoute | null {
    if (this.compteId === null) {
      return null;
    }
    const fileVide = this.scans().length === 0;
    const enDirect = this.peutEnvoyer() && this.scans().every((scan) => scan.etat === 'EN_ENVOI');
    const scan = creerScanEnFile(
      this.compteId,
      courseId,
      jetonQr,
      enDirect ? 'EN_ENVOI' : 'EN_ATTENTE',
    );
    this.attenteConstatee ||= !enDirect;
    this.scans.update((scans) => [...scans, scan]);
    this.nonEcrits.update((ids) => new Set(ids).add(scan.identifiantScan));
    const ecrit = this.ecritures.then(() => this.stockage.enregistrer(scan));
    this.ecritures = ecrit;
    void ecrit.then(() => this.marquerEcrit(scan.identifiantScan));
    if (fileVide || enDirect) {
      this.relancer();
    }
    return { scan, ecrit };
  }

  /** « Renvoyer maintenant » : nouvelle tentative immédiate, y compris après un accès refusé. */
  renvoyerMaintenant(): void {
    if (this.pause()?.cause === 'ACCES_REFUSE') {
      this.pause.set(null);
    }
    this.relancer();
  }

  effacerMessageEnvoyes(): void {
    this.envoyes.set(null);
  }

  private marquerEcrit(identifiantScan: string): void {
    this.nonEcrits.update((ids) => {
      const restants = new Set(ids);
      restants.delete(identifiantScan);
      return restants;
    });
  }

  private changerDeCompte(compteId: string | null): void {
    clearTimeout(this.minuterie);
    this.compteId = compteId;
    this.scans.set([]);
    this.envoyes.set(null);
    this.attenteConstatee = false;
    this.succesDepuisAttente = 0;
    if (compteId !== null) {
      clearTimeout(this.redirection);
      this.pause.set(null);
      this.relancer();
    }
  }

  /** Tentative immédiate : l'attente en cours est annulée et le compteur de pannes repart de zéro. */
  private relancer(): void {
    clearTimeout(this.minuterie);
    this.pannesConsecutives = 0;
    if (this.cycleEnCours) {
      this.relanceDemandee = true;
      return;
    }
    void this.cycle();
  }

  private peutEnvoyer(): boolean {
    return this.compteId !== null && this.pause() === null && navigator.onLine;
  }

  private async cycle(): Promise<void> {
    this.cycleEnCours = true;
    this.relanceDemandee = false;
    try {
      await this.envoyerLaFile();
      if (!this.relanceDemandee) {
        await this.bloquerLesEnvois();
      }
    } catch {
      this.planifier(attenteApresPanne(++this.pannesConsecutives));
    } finally {
      this.cycleEnCours = false;
    }
    if (this.relanceDemandee) {
      this.relancer();
    }
  }

  /** Envoie les entrées une à une ; s'arrête à la première panne ou pause, ou file vide. */
  private async envoyerLaFile(): Promise<void> {
    if (this.compteId === null) {
      return;
    }
    await this.purgerAuDemarrage();
    let jetonCsrfObtenu = false;
    for (let scan = await this.relire(); scan && this.peutEnvoyer(); scan = await this.relire()) {
      if (estTropAncien(scan)) {
        await this.retirer(scan);
        this.emettre(scan, { type: 'refus', refus: REFUS_TROP_ANCIEN, enDirect: false });
        continue;
      }
      if (!jetonCsrfObtenu && !(await this.obtenirJetonCsrf())) {
        await this.constaterPanne(scan);
        return;
      }
      jetonCsrfObtenu = true;
      if (!(await this.traiter(scan, await this.envoyerAvecCsrf(scan)))) {
        return;
      }
    }
  }

  /** Relit la base locale (autres onglets compris) ; renvoie la plus ancienne entrée du Compte. */
  private async relire(): Promise<ScanEnFile | null> {
    let tous: ScanEnFile[];
    let ecritures: Promise<void>;
    do {
      ecritures = this.ecritures;
      await ecritures;
      tous = await this.stockage.lister();
    } while (ecritures !== this.ecritures);
    const miens = tous
      .filter((scan) => scan.compteId === this.compteId)
      .map((scan): ScanEnFile =>
        estDeCetteSessionDePage(scan) ? scan : { ...scan, etat: 'EN_ATTENTE' },
      );
    this.scans.set(miens);
    return miens[0] ?? null;
  }

  /** Au démarrage : toute entrée de plus de 24 h, de tout Compte, est supprimée sans affichage. */
  private async purgerAuDemarrage(): Promise<void> {
    if (this.purgeFaite) {
      return;
    }
    this.purgeFaite = true;
    const tropAnciens = (await this.stockage.lister()).filter(
      (scan) => !estDeCetteSessionDePage(scan) && estTropAncien(scan),
    );
    for (const scan of tropAnciens) {
      await this.stockage.supprimer(scan.identifiantScan);
    }
  }

  private async obtenirJetonCsrf(): Promise<boolean> {
    try {
      await firstValueFrom(this.csrf.obtenirJeton().pipe(timeout(DELAI_REQUETE_MILLISECONDES)));
      return true;
    } catch {
      return false;
    }
  }

  /** Jeton CSRF refusé : un nouveau jeton et une seule nouvelle tentative, sinon panne. */
  private async envoyerAvecCsrf(scan: ScanEnFile): Promise<IssueEnvoi> {
    const issue = await this.envoyer(scan);
    if (issue.type !== 'csrf-invalide') {
      return issue;
    }
    const nouvelle = (await this.obtenirJetonCsrf()) ? await this.envoyer(scan) : issue;
    return nouvelle.type === 'csrf-invalide' ? { type: 'panne' } : nouvelle;
  }

  private async envoyer(scan: ScanEnFile): Promise<IssueEnvoi> {
    const requete = {
      jetonQr: scan.jetonQr,
      identifiantScan: scan.identifiantScan,
      ageDuScanMillisecondes: ageDuScan(scan),
    };
    try {
      const passage = await firstValueFrom(
        this.benevoleApi
          .enregistrerPassage(scan.courseId, requete)
          .pipe(timeout(DELAI_REQUETE_MILLISECONDES)),
      );
      return { type: 'succes', passage };
    } catch (erreur: unknown) {
      return classerErreur(erreur);
    }
  }

  /** Applique l'issue d'un envoi ; renvoie `true` si le cycle continue avec l'entrée suivante. */
  private async traiter(scan: ScanEnFile, issue: IssueEnvoi): Promise<boolean> {
    switch (issue.type) {
      case 'succes':
        this.pannesConsecutives = 0;
        this.succesDepuisAttente += this.attenteConstatee ? 1 : 0;
        await this.retirer(scan);
        this.emettre(scan, issue);
        return true;
      case 'refus':
        this.pannesConsecutives = 0;
        await this.retirer(scan);
        this.emettre(scan, { ...issue, enDirect: scan.etat === 'EN_ENVOI' });
        return true;
      case 'session-expiree':
      case 'acces-refuse':
        await this.mettreEnPause(scan, issue.type, issue.erreur);
        return false;
      default:
        await this.constaterPanne(
          scan,
          issue.type === 'panne' ? issue.reessayerApresSecondes : undefined,
        );
        return false;
    }
  }

  private async mettreEnPause(
    scan: ScanEnFile,
    cause: 'session-expiree' | 'acces-refuse',
    erreur: HttpErrorResponse,
  ): Promise<void> {
    await this.marquerEnAttente(scan, 0);
    const ecran = this.router.url;
    if (cause === 'acces-refuse') {
      this.pause.set({ cause: 'ACCES_REFUSE', enAttente: this.scans().length });
      this.refusAcces.rediriger(erreur, ecran);
      return;
    }
    this.pause.set({ cause: 'SESSION_EXPIREE', enAttente: this.scans().length });
    this.redirection = setTimeout(
      () => this.refusAcces.rediriger(erreur, ecran),
      DELAI_AVANT_RECONNEXION_MILLISECONDES,
    );
  }

  /** Fin de cycle : une entrée restée en première tentative est désormais gardée en attente. */
  private async bloquerLesEnvois(): Promise<void> {
    for (const scan of this.scans().filter((s) => s.etat === 'EN_ENVOI')) {
      await this.marquerEnAttente(scan, 0);
    }
  }

  private async constaterPanne(scan: ScanEnFile, reessayerApresSecondes?: number): Promise<void> {
    await this.marquerEnAttente(scan, 1);
    this.planifier(attenteApresPanne(++this.pannesConsecutives, reessayerApresSecondes));
  }

  private planifier(attenteMillisecondes: number): void {
    clearTimeout(this.minuterie);
    this.minuterie = setTimeout(() => void this.cycle(), attenteMillisecondes);
  }

  private async marquerEnAttente(scan: ScanEnFile, tentativesEnPlus: number): Promise<void> {
    const enAttente: ScanEnFile = {
      ...scan,
      etat: 'EN_ATTENTE',
      tentatives: scan.tentatives + tentativesEnPlus,
    };
    this.attenteConstatee = true;
    this.scans.update((scans) =>
      scans.map((s) => (s.identifiantScan === scan.identifiantScan ? enAttente : s)),
    );
    await this.stockage.enregistrer(enAttente);
    this.emettre(scan, { type: 'attente' });
  }

  /** Supprime l'entrée (et son jeton) ; annonce les scans envoyés quand la file se vide. */
  private async retirer(scan: ScanEnFile): Promise<void> {
    await this.stockage.supprimer(scan.identifiantScan);
    this.scans.update((scans) => scans.filter((s) => s.identifiantScan !== scan.identifiantScan));
    if (this.scans().length > 0 || !this.attenteConstatee) {
      return;
    }
    if (this.succesDepuisAttente > 0) {
      this.envoyes.set(this.succesDepuisAttente);
    }
    this.attenteConstatee = false;
    this.succesDepuisAttente = 0;
  }

  private emettre(scan: ScanEnFile, issue: IssueScan): void {
    this.evenements.next({
      identifiantScan: scan.identifiantScan,
      courseId: scan.courseId,
      ...issue,
    });
  }
}
