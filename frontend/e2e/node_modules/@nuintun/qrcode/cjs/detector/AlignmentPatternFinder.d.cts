/**
 * @module AlignmentPatternFinder
 */
import { Pattern } from './Pattern.cjs';
import { BitMatrix } from '../common/BitMatrix.cjs';
import { PatternFinder } from './PatternFinder.cjs';
export declare class AlignmentPatternFinder extends PatternFinder {
  constructor(matrix: BitMatrix, strict?: boolean);
  filter(expectAlignment: Pattern, moduleSize: number): Pattern[];
  find(left: number, top: number, width: number, height: number): void;
}
