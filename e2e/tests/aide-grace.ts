import { expect, type PlaywrightWorkerArgs } from '@playwright/test';
import { lireHorloge, avancerHorloge } from './aide-horloge';
import { executerSql } from './aide-scan';

type PlaywrightLib = PlaywrightWorkerArgs['playwright'];

/** Départ de la Course (ms epoch), lu en base. */
export function lireDepartCourseMs(courseId: string): number {
  if (!/^[0-9a-f-]{36}$/.test(courseId)) throw new Error('identifiant de course invalide');
  return Number(executerSql(`select extract(epoch from demarree_le) * 1000 from course where id = '${courseId}'`));
}

/**
 * Avance l'horloge applicative pour que l'instant serveur soit à `secondes` secondes après le départ de la Course
 * (7.1 : positionnement exact dans le délai de grâce, indépendant du temps réel écoulé pendant la préparation).
 */
export async function positionnerHorlogeApresDepart(playwright: PlaywrightLib, courseId: string, secondes: number): Promise<void> {
  const maintenant = new Date((await lireHorloge(playwright)).maintenant).getTime();
  const ecartSecondes = (maintenant - lireDepartCourseMs(courseId)) / 1000;
  const aAvancer = secondes - ecartSecondes;
  expect(aAvancer, 'le temps réel écoulé dépasse déjà la position visée').toBeGreaterThan(0);
  await avancerHorloge(playwright, `PT${aAvancer.toFixed(3)}S`);
}
