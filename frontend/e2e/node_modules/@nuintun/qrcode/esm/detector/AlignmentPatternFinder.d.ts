/**
 * @module AlignmentPatternFinder
 */
import { Pattern } from './Pattern.js';
import { BitMatrix } from '../common/BitMatrix.js';
import { PatternFinder } from './PatternFinder.js';
export declare class AlignmentPatternFinder extends PatternFinder {
  constructor(matrix: BitMatrix, strict?: boolean);
  filter(expectAlignment: Pattern, moduleSize: number): Pattern[];
  find(left: number, top: number, width: number, height: number): void;
}
