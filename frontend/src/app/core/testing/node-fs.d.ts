/**
 * Declaration minimale de `node:fs` pour les tests de lecture de sources (inc. 6, RG2 a RG5) : le projet n'embarque
 * pas @types/node (`types: []`). Uniquement ce que ces tests utilisent ; aucun code de production n'en depend.
 */
declare module 'node:fs' {
  export interface DirectoryEntry {
    readonly name: string;
    isDirectory(): boolean;
  }
  export function readFileSync(path: URL, encoding: 'utf8'): string;
  export function readdirSync(path: URL, options: { withFileTypes: true }): DirectoryEntry[];
  export function existsSync(path: URL): boolean;
}
