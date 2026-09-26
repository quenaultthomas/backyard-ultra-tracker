/**
 * @module FinderPatternFinder
 */
import { BitMatrix } from '../common/BitMatrix.cjs';
import { PatternFinder } from './PatternFinder.cjs';
import { FinderPatternGroup } from './FinderPatternGroup.cjs';
export declare class FinderPatternFinder extends PatternFinder {
  constructor(matrix: BitMatrix, strict?: boolean);
  groups(): Generator<FinderPatternGroup, void, boolean>;
  find(left: number, top: number, width: number, height: number): void;
}
