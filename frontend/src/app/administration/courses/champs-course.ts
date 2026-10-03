import { DeclarerCourseRequete } from './course';

/** Champs de `DeclarerCourseRequete`, dans l'ordre du formulaire et des `erreurs` de l'API. */
export type ChampCourse = keyof DeclarerCourseRequete;
export type ChampNombreCourse = Exclude<ChampCourse, 'nom' | 'date'>;

export const CHAMPS_COURSE: readonly ChampCourse[] = [
  'nom',
  'date',
  'distanceBoucleMetres',
  'dureeBoucleMinutes',
  'denivelePositifBoucleMetres',
  'nombreMaxParticipants',
  'nombreMaxBoucles',
];

/** Description d'un champ numérique : saisie, contrôle de confort et affichage en liste. */
export interface DescriptionChampNombre {
  readonly cle: ChampNombreCourse;
  /** Suffixe des `data-testid` (`course-champ-<suffixe>`, `course-erreur-<suffixe>`, `course-<suffixe>`). */
  readonly suffixe: string;
  readonly libelle: string;
  readonly libelleCourt: string;
  /** Sujet des messages d'erreur (« La distance d'une boucle »). */
  readonly sujet: string;
  /** Plus petite valeur acceptée par le contrôle de confort (0 pour une Boucle plate). */
  readonly minimum: 0 | 1;
  readonly unite: string;
}

export const CHAMPS_NOMBRE: readonly DescriptionChampNombre[] = [
  {
    cle: 'distanceBoucleMetres',
    suffixe: 'distance',
    libelle: "Distance d'une boucle (m)",
    libelleCourt: 'Distance',
    sujet: "La distance d'une boucle",
    minimum: 1,
    unite: ' m',
  },
  {
    cle: 'dureeBoucleMinutes',
    suffixe: 'duree',
    libelle: "Durée d'une boucle (min)",
    libelleCourt: 'Durée',
    sujet: "La durée d'une boucle",
    minimum: 1,
    unite: ' min',
  },
  {
    cle: 'denivelePositifBoucleMetres',
    suffixe: 'denivele',
    libelle: "Dénivelé positif d'une boucle (m)",
    libelleCourt: 'Dénivelé positif',
    sujet: "Le dénivelé positif d'une boucle",
    minimum: 0,
    unite: ' m',
  },
  {
    cle: 'nombreMaxParticipants',
    suffixe: 'participants-max',
    libelle: 'Nombre maximum de participants',
    libelleCourt: 'Participants max.',
    sujet: 'Le nombre maximum de participants',
    minimum: 1,
    unite: '',
  },
  {
    cle: 'nombreMaxBoucles',
    suffixe: 'boucles-max',
    libelle: 'Nombre maximum de boucles',
    libelleCourt: 'Boucles max.',
    sujet: 'Le nombre maximum de boucles',
    minimum: 1,
    unite: '',
  },
];

/**
 * Valeurs saisies : texte pour le nom, date en `jj/mm/aaaa` (format de l'écran, converti
 * avant l'envoi), `null` pour un nombre non renseigné.
 */
export type SaisieCourse = Pick<DeclarerCourseRequete, 'nom' | 'date'> &
  Record<ChampNombreCourse, number | null>;

const FORMAT_DATE_SAISIE = /^(\d{2})\/(\d{2})\/(\d{4})$/;

/**
 * Date saisie `jj/mm/aaaa` (espaces autour tolérés) vers `aaaa-mm-jj`, ou `null` si le format
 * est invalide ou si le jour n'existe pas. Calcul sur les chiffres, sans `Date` (aucun fuseau).
 */
export function convertirDateSaisie(saisie: string): string | null {
  const parties = FORMAT_DATE_SAISIE.exec(saisie.trim());
  if (parties === null) {
    return null;
  }
  const [, jour, mois, annee] = parties;
  const numeroMois = Number(mois);
  const numeroJour = Number(jour);
  if (numeroMois < 1 || numeroMois > 12) {
    return null;
  }
  if (numeroJour < 1 || numeroJour > joursDansLeMois(numeroMois, Number(annee))) {
    return null;
  }
  return `${annee}-${mois}-${jour}`;
}

/** `aaaa-mm-jj` (API) vers `jj/mm/aaaa` (écran), par simple réorganisation. */
export function formaterDateCourse(date: string): string {
  return date.split('-').reverse().join('/');
}

function joursDansLeMois(mois: number, annee: number): number {
  if (mois === 2) {
    const bissextile = (annee % 4 === 0 && annee % 100 !== 0) || annee % 400 === 0;
    return bissextile ? 29 : 28;
  }
  return [4, 6, 9, 11].includes(mois) ? 30 : 31;
}

/** Saisie contrôlée vers le corps de la requête (date convertie en `aaaa-mm-jj`). */
export function versRequeteDeclaration(saisie: SaisieCourse): DeclarerCourseRequete {
  // Appelée après `controlerSaisieCourse` : date valide et chaque nombre renseigné.
  return { ...saisie, date: convertirDateSaisie(saisie.date) ?? '' } as DeclarerCourseRequete;
}

/**
 * Contrôles de confort (champs renseignés, format de date, entiers positifs) : les bornes,
 * la longueur du nom et la plage de dates sont contrôlées par l'API, seule référence.
 */
export function controlerSaisieCourse(saisie: SaisieCourse): Partial<Record<ChampCourse, string>> {
  const erreurs: Partial<Record<ChampCourse, string>> = {};
  if (saisie.nom.trim() === '') {
    erreurs.nom = 'Le nom est obligatoire.';
  }
  const messageDate = controlerDate(saisie.date);
  if (messageDate !== undefined) {
    erreurs.date = messageDate;
  }
  for (const champ of CHAMPS_NOMBRE) {
    const message = controlerNombre(saisie[champ.cle], champ);
    if (message !== undefined) {
      erreurs[champ.cle] = message;
    }
  }
  return erreurs;
}

function controlerDate(saisie: string): string | undefined {
  if (saisie.trim() === '') {
    return 'La date est obligatoire.';
  }
  return convertirDateSaisie(saisie) === null
    ? 'La date doit être au format jj/mm/aaaa.'
    : undefined;
}

function controlerNombre(
  valeur: number | null,
  { sujet, minimum }: DescriptionChampNombre,
): string | undefined {
  if (valeur === null) {
    return `${sujet} est obligatoire.`;
  }
  if (Number.isInteger(valeur) && valeur >= minimum) {
    return undefined;
  }
  const borne = minimum === 0 ? 'supérieur ou égal à 0' : 'supérieur à 0';
  return `${sujet} doit être un nombre entier ${borne}.`;
}
