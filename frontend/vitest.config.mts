import { defineConfig } from 'vitest/config';

/**
 * Tests unitaires de la logique pure du front (RG59, CA5) : sans navigateur, sans backend, sans TestBed.
 * Le perimetre de couverture est la liste des fichiers de src/app/core (hors tests et types seuls),
 * avec un seuil bloquant de 80 % de lignes.
 */
export default defineConfig({
  test: {
    environment: 'node',
    include: ['src/app/core/**/*.spec.ts'],
    reporters: ['default'],
    coverage: {
      provider: 'v8',
      include: ['src/app/core/**/*.ts'],
      exclude: ['src/app/core/**/*.spec.ts', 'src/app/core/**/*.spec-support.ts', 'src/app/core/**/*.types.ts'],
      reporter: ['text', 'text-summary', 'html', 'json-summary'],
      reportsDirectory: 'coverage',
      thresholds: {
        lines: 80
      }
    }
  }
});
