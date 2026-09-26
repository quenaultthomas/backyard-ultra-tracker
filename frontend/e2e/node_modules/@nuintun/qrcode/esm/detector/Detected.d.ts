/**
 * @module Detected
 */
import { Pattern } from './Pattern.js';
import { Point } from '../common/Point.js';
import { BitMatrix } from '../common/BitMatrix.js';
import { FinderPatternGroup } from './FinderPatternGroup.js';
import { PerspectiveTransform } from '../common/PerspectiveTransform.js';
export declare class Detected {
  #private;
  constructor(
    matrix: BitMatrix,
    transform: PerspectiveTransform,
    finderPatternGroup: FinderPatternGroup,
    alignmentPattern?: Pattern
  );
  /**
   * @property matrix
   * @description Get the matrix.
   */
  get matrix(): BitMatrix;
  /**
   * @property finder
   * @description Get the finder pattern.
   */
  get finder(): FinderPatternGroup;
  /**
   * @property alignment
   * @description Get the alignment pattern.
   */
  get alignment(): Pattern | undefined;
  /**
   * @property size
   * @description Get the size.
   */
  get size(): number;
  /**
   * @property moduleSize
   * @description Get the module size.
   */
  get moduleSize(): number;
  /**
   * @method mapping
   * @description Get the mapped point.
   * @param x The x of point.
   * @param y The y of point.
   */
  mapping(x: number, y: number): Point;
}
