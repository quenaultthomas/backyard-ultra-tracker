import { describe, expect, it } from 'vitest';
import {
  classify,
  classifyResponse,
  errorMessage,
  fieldErrors,
  isServerUnreachable,
  RawHttpResult,
  readProblem,
} from './http-classification';
import { jsonResponse, problem } from './testing/fakes.spec-support';

const html502: RawHttpResult = { kind: 'response', status: 502, json: false, body: '<html>Bad Gateway</html>' };

describe('CA9 - classification des réponses (RG3, RG24)', () => {
  it.each<[string, RawHttpResult, string]>([
    ['200', jsonResponse(200, {}), 'SUCCESS'],
    ['201', jsonResponse(201, {}), 'SUCCESS'],
    ['204 sans contenu', jsonResponse(204, null), 'SUCCESS'],
    ['400 INVALID_INPUT', problem(400, 'INVALID_INPUT', 'scan dans le futur'), 'DEFINITIVE'],
    ['400 VALIDATION_FAILED', problem(400, 'VALIDATION_FAILED', 'Requête invalide'), 'DEFINITIVE'],
    ['401', problem(401, 'UNAUTHENTICATED', 'Identifiants invalides'), 'AUTH'],
    ['403', problem(403, 'ACCESS_DENIED', 'accès refusé pour le rôle SCANNER'), 'ROLE'],
    ['404', problem(404, 'RESOURCE_NOT_FOUND', 'inconnu'), 'DEFINITIVE'],
    ['405', problem(405, 'METHOD_NOT_ALLOWED', 'non supportée'), 'DEFINITIVE'],
    ['409 BUSINESS_CONFLICT', problem(409, 'BUSINESS_CONFLICT', 'scan tardif'), 'DEFINITIVE'],
    ['409 DATA_INTEGRITY', problem(409, 'DATA_INTEGRITY', 'réessayer'), 'TRANSIENT'],
    ['415', problem(415, 'MALFORMED_REQUEST', 'type'), 'DEFINITIVE'],
    ['422 (autre 4xx)', problem(422, 'X', 'autre'), 'DEFINITIVE'],
    ['500', problem(500, 'INTERNAL_ERROR', 'erreur'), 'TRANSIENT'],
    ['502 avec un corps HTML', html502, 'TRANSIENT'],
    ['200 non JSON (portail captif)', { kind: 'response', status: 200, json: false, body: '<html>' }, 'TRANSIENT'],
    ['erreur réseau', { kind: 'network-error' }, 'TRANSIENT'],
    ['délai de 10 s dépassé', { kind: 'timeout' }, 'TRANSIENT'],
  ])('%s -> %s', (_label, raw, expected) => {
    expect(classify(raw).responseClass).toBe(expected);
  });

  it('classe d\'un 3xx ou 1xx inattendu : TRANSITOIRE', () => {
    expect(classifyResponse(302, true, null)).toBe('TRANSIENT');
  });

  it('succès : corps transmis, pas de ProblemDetail', () => {
    const result = classify<{ bib: number }>(jsonResponse(200, { bib: 7 }));
    expect(result.body).toEqual({ bib: 7 });
    expect(result.problem).toBeNull();
    expect(result.failure).toBeNull();
  });

  it('erreur : ProblemDetail lu (status, code, detail, errors), corps non transmis', () => {
    const result = classify(jsonResponse(400, {
      status: 400, code: 'VALIDATION_FAILED', detail: 'Requête invalide : 1 champ(s) en erreur (name)',
      errors: [{ field: 'name', message: 'ne doit pas être vide' }, { field: 'name', message: 'trop long' }, 'x'],
    }));
    expect(result.body).toBeNull();
    expect(result.problem).toEqual({
      status: 400,
      code: 'VALIDATION_FAILED',
      detail: 'Requête invalide : 1 champ(s) en erreur (name)',
      errors: [{ field: 'name', message: 'ne doit pas être vide' }, { field: 'name', message: 'trop long' }],
    });
    expect(fieldErrors(result).get('name')).toBe('ne doit pas être vide ; trop long');
  });

  it('ProblemDetail illisible : champs à null, sans erreur', () => {
    expect(readProblem(500, 'texte')).toEqual({ status: 500, code: null, detail: null, errors: [] });
    expect(readProblem(400, { errors: 'non' }).errors).toEqual([]);
  });

  it('message affiché : detail du serveur, sinon « Serveur injoignable » (réseau, non JSON, 5xx)', () => {
    expect(errorMessage(classify(problem(409, 'BUSINESS_CONFLICT', 'Course terminée')))).toBe('Course terminée');
    expect(errorMessage(classify({ kind: 'network-error' }))).toBe('Serveur injoignable');
    expect(errorMessage(classify(html502))).toBe('Serveur injoignable');
    expect(errorMessage(classify(jsonResponse(503, {})))).toBe('Serveur injoignable');
    expect(errorMessage(classify(jsonResponse(418, {})))).toBe('Erreur 418');
  });

  it('serveur injoignable : réseau, délai, non JSON ou 5xx', () => {
    expect(isServerUnreachable(classify({ kind: 'timeout' }))).toBe(true);
    expect(isServerUnreachable(classify(jsonResponse(500, {})))).toBe(true);
    expect(isServerUnreachable(classify(problem(404, 'RESOURCE_NOT_FOUND', 'x')))).toBe(false);
  });
});
