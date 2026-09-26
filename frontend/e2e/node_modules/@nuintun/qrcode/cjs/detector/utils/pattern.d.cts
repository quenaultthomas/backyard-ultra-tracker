/**
 * @module pattern
 */
import { BitMatrix } from '../../common/BitMatrix.cjs';
import { PatternRatios } from '../PatternRatios.cjs';
export declare function isDiagonalScanlineCheckPassed(
  slash: number[],
  backslash: number[],
  ratios: PatternRatios,
  strict?: boolean
): boolean;
export declare function alignCrossPattern(
  matrix: BitMatrix,
  x: number,
  y: number,
  overscan: number,
  ratios: PatternRatios,
  isVertical?: boolean
): [center: number, scanline: number[]];
export declare function isEqualsSize(size1: number, size2: number, ratio: number): boolean;
export declare function isMatchPattern(scanline: number[], { ratios, modules }: PatternRatios): boolean;
export declare function calculatePatternNoise(ratios: PatternRatios, ...scanlines: number[][]): number;
