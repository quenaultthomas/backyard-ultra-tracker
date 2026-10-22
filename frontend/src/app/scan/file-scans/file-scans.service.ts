import { HttpErrorResponse } from '@angular/common/http';
import { DOCUMENT, Injectable, computed, effect, inject, signal, untracked } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, Subject, firstValueFrom, timeout } from 'rxjs';

import { RefusAccesService } from '../../comptes/refus-acces.service';
import { estBenevole } from '../../comptes/roles';
import { SessionService } from '../../comptes/session.service';
import { CsrfService } from '../../partage/csrf.service';
import { ReseauService } from '../../partage/reseau.service';
import { BenevoleApiService } from '../benevole-api.service';
import { PassageReponse } from '../passage';
import { RefusScan } from '../refus-scan';
import { IssueEnvoi, attenteApresPanne, classerErreur } from './issue-envoi';
import {
  EntreeFile,
  ScanAVerifier,
  ScanEnFile,
  ageDuScan,
  creerScanEnFile,
  estAVerifier,
  estDeCetteSessionDePage,
  mettreAVerifier,
} from './scan-en-file';
import { StockageScans } from './stockage-scans';

/** Au-delà, la tentative est une panne (le serveur a pu traiter : le rejeu est sans effet). */
const DELAI_REQUETE_MILLISECONDES = 10_000;
/** Le message de session expirée reste lisible avant la redirection vers la connexion. */
const DELAI_AVANT_RECONNEXION_MILLISECONDES = 2_000;

/** `refus` : refus en direct (première tentative) ; `a-verifier` : refus différé, mis dans la liste. */
type IssueScan =
  | { type: 'succes'; passage: PassageReponse }
  | { type: 'refus'; refus: RefusScan }
  | { type: 'a-verifier' }
  | { type: 'attente' };

/** Ce qui arrive à un scan de la file, pour l'écran de sa Course. */
export type EvenementScan = { identifiantScan: string; courseId: string } & IssueScan;

/** Scan mis en file ; `ecrit` se résout quand l'entrée est durablement écrite (jamais rejetée). */
export interface ScanAjoute {
  scan: ScanEnFile;
  ecrit: Promise<void>;
}

/** Fin d'une attente : scans envoyés et scans passés « à vérifier » pendant le cycle. */
export interface FinEnvoi {
  envoyes: number;
  aVerifier: number;
}

interface Pause {
  cause: 'SESSION_EXPIREE' | 'ACCES_REFUSE';
  enAttente: number;
}

/**
 * File locale des scans du bénévole connecté : chaque scan est écrit avant d'être envoyé, puis
 * envoyé un à la fois dans l'ordre de création, avec nouvelles tentatives après une panne.
 * Le serveur décide de tout (Boucle, refus) ; la file ne fait que classer ses réponses. Un refus
 * reçu en différé (ou un scan trop ancien) reste sur l'appareil, sans jeton, « à vérifier ».
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

  /** Entrées à envoyer du Compte connecté, dans l'ordre d'envoi. */
  private readonly scans = signal<ScanEnFile[]>([]);
  /** Scans à vérifier du Compte connecté, toutes Courses confondues, dans l'ordre de création. */
  private readonly scansAVerifier = signal<ScanAVerifier[]>([]);
  /** Entrées créées dont l'écriture locale n'est pas terminée : pas encore comptées. */
  private readonly nonEcrits = signal<ReadonlySet<string>>(new Set());
  private readonly pause = signal<Pause | null>(null);
  private readonly finEnvoi = signal<FinEnvoi | null>(null);
  private readonly evenements = new Subject<EvenementScan>();

  readonly horsLigne = inject(ReseauService).horsLigne;
  readonly stockagePersistant = this.stockage.persistant;
  /** Bilan de la file quand elle vient de se vider après une attente. */
  readonly scansEnvoyes = this.finEnvoi.asReadonly();
  /** Nombre de scans en attente quand la session a expiré pendant un envoi. */
  readonly sessionExpiree = computed(() => {
    const pause = this.pause();
    return pause?.cause === 'SESSION_EXPIREE' ? pause.enAttente : null;
  });
  readonly evenements$: Observable<EvenementScan> = this.evenements.asObservable();
  /** Entrées en attente, une fois écrites sur l'appareil (la première tentative n'en est pas). */
  private readonly enAttenteEcrits = computed(() => {
    const nonEcrits = this.nonEcrits();
    return this.scans().filter(
      (scan) => scan.etat === 'EN_ATTENTE' && !nonEcrits.has(scan.identifiantScan),
    );
  });
  /** Total du Compte connecté, toutes Courses confondues. */
  readonly totalEnAttente = computed(() => this.enAttenteEcrits().length);
  readonly totalAVerifier = computed(() => this.scansAVerifier().length);

  private compteId: string | null = null;
  private ecritures: Promise<void> = Promise.resolve();
  private cycleEnCours = false;
  private relanceDemandee = false;
  private pannesConsecutives = 0;
  private minuterie: ReturnType<typeof setTimeout> | undefined;
  private redirection: ReturnType<typeof setTimeout> | undefined;
  private attenteConstatee = false;
  private succesDepuisAttente = 0;
  private aVerifierDepuisAttente = 0;

  constructor() {
    const fenetre = this.document.defaultView;
    fenetre?.addEventListener('online', () => this.relancer());
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

  /** Entrées en attente du Compte connecté pour une Course. */
  enAttente(courseId: string): number {
    return this.enAttenteEcrits().filter((scan) => scan.courseId === courseId).length;
  }

  /** Scans à vérifier du Compte connecté pour une Course. */
  aVerifier(courseId: string): ScanAVerifier[] {
    return this.scansAVerifier().filter((scan) => scan.courseId === courseId);
  }

  /** « Compris » : la ligne disparaît de la liste et de l'appareil (Compte connecté seulement). */
  marquerCompris(identifiantScan: string): void {
    if (!this.scansAVerifier().some((scan) => scan.identifiantScan === identifiantScan)) {
      return;
    }
    this.scansAVerifier.update((scans) =>
      scans.filter((scan) => scan.identifiantScan !== identifiantScan),
    );
    this.ecritures = this.ecritures.then(() => this.stockage.supprimer(identifiantScan));
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
    this.finEnvoi.set(null);
  }

  private marquerEcrit(identifiantScan: string): void {
    this.nonEcrits.update((ids) => {
      const restants = new Set(ids);
      restants.delete(identifiantScan);
      return restants;
    });
  }

  private changerDeCompte(compteId: string | null): void {
    if (compteId === this.compteId) {
      // Même Compte relu (revalidation au retour du réseau) : la file et son bilan continuent.
      return;
    }
    clearTimeout(this.minuterie);
    this.compteId = compteId;
    this.scans.set([]);
    this.scansAVerifier.set([]);
    this.finEnvoi.set(null);
    this.attenteConstatee = false;
    this.succesDepuisAttente = 0;
    this.aVerifierDepuisAttente = 0;
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
    let jetonCsrfObtenu = false;
    for (let scan = await this.relire(); scan && this.peutEnvoyer(); scan = await this.relire()) {
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

  /**
   * Relit la base locale (autres onglets compris) : scans à envoyer et à vérifier du Compte ;
   * renvoie la plus ancienne entrée à envoyer.
   */
  private async relire(): Promise<ScanEnFile | null> {
    let tous: EntreeFile[];
    let ecritures: Promise<void>;
    do {
      ecritures = this.ecritures;
      await ecritures;
      tous = await this.stockage.lister();
    } while (ecritures !== this.ecritures);
    const miens = tous.filter((entree) => entree.compteId === this.compteId);
    const aEnvoyer = miens
      .filter((entree): entree is ScanEnFile => !estAVerifier(entree))
      .map((scan): ScanEnFile =>
        estDeCetteSessionDePage(scan) ? scan : { ...scan, etat: 'EN_ATTENTE' },
      );
    this.scansAVerifier.set(miens.filter(estAVerifier));
    this.scans.set(aEnvoyer);
    // Une entrée d'avant le rechargement (ou d'un autre onglet) était en attente : le bilan sera annoncé.
    this.attenteConstatee ||= aEnvoyer.some((scan) => !estDeCetteSessionDePage(scan));
    return aEnvoyer[0] ?? null;
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
        if (scan.etat === 'EN_ATTENTE') {
          await this.mettreDeCote(scan, issue.refus);
          return true;
        }
        await this.retirer(scan);
        this.emettre(scan, issue);
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
    if (this.session.etat() === 'ANONYME') {
      // Session déjà constatée expirée (revalidation au retour du réseau) : la redirection est faite.
      return;
    }
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

  /** Supprime l'entrée (et son jeton). */
  private async retirer(scan: ScanEnFile): Promise<void> {
    await this.stockage.supprimer(scan.identifiantScan);
    this.sortirDeLaFile(scan);
  }

  /**
   * Refus différé ou scan trop ancien : l'entrée devient « à vérifier » (son jeton est effacé),
   * jamais retentée.
   */
  private async mettreDeCote(scan: ScanEnFile, refus: RefusScan): Promise<void> {
    const aVerifier = mettreAVerifier(scan, refus);
    await this.stockage.enregistrer(aVerifier);
    this.scansAVerifier.update((scans) => [...scans, aVerifier]);
    this.aVerifierDepuisAttente += this.attenteConstatee ? 1 : 0;
    this.sortirDeLaFile(scan);
    this.emettre(scan, { type: 'a-verifier' });
  }

  /** Annonce le bilan (envoyés, à vérifier) quand la file se vide après une attente. */
  private sortirDeLaFile(scan: ScanEnFile): void {
    this.scans.update((scans) => scans.filter((s) => s.identifiantScan !== scan.identifiantScan));
    if (this.scans().length > 0 || !this.attenteConstatee) {
      return;
    }
    if (this.succesDepuisAttente > 0 || this.aVerifierDepuisAttente > 0) {
      this.finEnvoi.set({
        envoyes: this.succesDepuisAttente,
        aVerifier: this.aVerifierDepuisAttente,
      });
    }
    this.attenteConstatee = false;
    this.succesDepuisAttente = 0;
    this.aVerifierDepuisAttente = 0;
  }

  private emettre(scan: ScanEnFile, issue: IssueScan): void {
    this.evenements.next({
      identifiantScan: scan.identifiantScan,
      courseId: scan.courseId,
      ...issue,
    });
  }
}
