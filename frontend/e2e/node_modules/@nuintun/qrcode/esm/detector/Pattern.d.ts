/**
 * @module Pattern
 */
import { Point } from '../common/Point.js';
import { PatternRatios } from './PatternRatios.js';
type PatternRect = readonly [left: number, top: number, right: number, bottom: number];
export declare class Pattern extends Point {
  #private;
  static noise(pattern: Pattern): number;
  static width(pattern: Pattern): number;
  static height(pattern: Pattern): number;
  static combined(pattern: Pattern): number;
  static rect(pattern: Pattern): PatternRect;
  static equals(pattern: Pattern, x: number, y: number, width: number, height: number): boolean;
  static combine(pattern: Pattern, x: number, y: number, width: number, height: number, noise: number): Pattern;
  constructor(ratios: PatternRatios, x: number, y: number, width: number, height: number, noise: number);
  get moduleSize(): number;
}
export {};
