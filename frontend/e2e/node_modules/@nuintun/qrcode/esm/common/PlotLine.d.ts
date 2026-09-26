/**
 * @module PlotLine
 */
import { Point } from './Point.js';
export declare class PlotLine {
  #private;
  constructor(from: Point, to: Point);
  points(): Generator<[x: number, y: number]>;
}
