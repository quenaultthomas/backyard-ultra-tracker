/**
 * @module scanline
 */
import { BitMatrix } from '../../common/BitMatrix.js';
import { PatternRatios } from '../PatternRatios.js';
export declare function calculateScanlineNoise(
  scanline: number[],
  { ratios, modules }: PatternRatios
): [noise: number, average: number];
export declare function sumScanlineNonzero(scanline: number[]): number;
export declare function scanlineUpdate(scanline: number[], count: number): void;
export declare function getCrossScanline(
  matrix: BitMatrix,
  x: number,
  y: number,
  overscan: number,
  isVertical?: boolean
): [scanline: number[], end: number];
export declare function getDiagonalScanline(
  matrix: BitMatrix,
  x: number,
  y: number,
  overscan: number,
  isBackslash?: boolean
): number[];
export declare function centerFromScanlineEnd(scanline: number[], end: number): number;
