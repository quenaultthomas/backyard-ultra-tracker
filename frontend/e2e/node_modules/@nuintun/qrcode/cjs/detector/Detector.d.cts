/**
 * @module Detector
 */
import { Detected } from './Detected.cjs';
import { BitMatrix } from '../common/BitMatrix.cjs';
export interface Options {
  /**
   * @property strict
   * @description Enable strict mode.
   */
  strict?: boolean;
}
export declare class Detector {
  #private;
  /**
   * @constructor
   * @param options The options of detector.
   */
  constructor(options?: Options);
  /**
   * @method detect Detect the binarized image matrix.
   * @param matrix The binarized image matrix.
   */
  detect(matrix: BitMatrix): Generator<Detected, void, boolean>;
}
