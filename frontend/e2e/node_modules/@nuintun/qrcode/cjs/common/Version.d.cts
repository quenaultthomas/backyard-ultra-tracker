/**
 * @module Version
 */
import { ECLevel } from './ECLevel.cjs';
import { ECBlocks } from './ECBlocks.cjs';
import { BitMatrix } from './BitMatrix.cjs';
export declare const MIN_VERSION_SIZE = 21;
export declare const MAX_VERSION_SIZE = 177;
export declare const MIN_VERSION_SIZE_WITH_ALIGNMENTS = 25;
export declare class Version {
  #private;
  constructor(version: number, alignmentPatterns: number[], ...ecBlocks: ECBlocks[]);
  get size(): number;
  get version(): number;
  get alignmentPatterns(): number[];
  getECBlocks({ level }: ECLevel): ECBlocks;
}
export declare const VERSIONS: Version[];
export declare function decodeVersion(version1: number, version2: number): Version;
export declare function buildFunctionPattern({ size, version, alignmentPatterns }: Version): BitMatrix;
