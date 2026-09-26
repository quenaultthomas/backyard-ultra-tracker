/**
 * @module FinderPatternFinder
 */
import { BitMatrix } from '../common/BitMatrix.js';
import { PatternFinder } from './PatternFinder.js';
import { FinderPatternGroup } from './FinderPatternGroup.js';
export declare class FinderPatternFinder extends PatternFinder {
  constructor(matrix: BitMatrix, strict?: boolean);
  groups(): Generator<FinderPatternGroup, void, boolean>;
  find(left: number, top: number, width: number, height: number): void;
}
