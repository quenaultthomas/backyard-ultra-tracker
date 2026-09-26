/**
 * @module PatternFinder
 */
import { Pattern } from './Pattern.cjs';
import { BitMatrix } from '../common/BitMatrix.cjs';
import { PatternRatios } from './PatternRatios.cjs';
export declare class PatternFinder {
  #private;
  constructor(matrix: BitMatrix, ratios: PatternRatios, strict?: boolean);
  get matrix(): BitMatrix;
  get patterns(): Pattern[];
  protected match(x: number, y: number, scanline: number[], overscan: number): void;
}
export interface MatchAction {
  (x: number, y: number, scanline: number[], count: number, scanlineBits: number[], lastBit: number): void;
}
