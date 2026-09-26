/**
 * @module GridSampler
 */
import { BitMatrix } from './BitMatrix.js';
import { PerspectiveTransform } from './PerspectiveTransform.js';
export declare class GridSampler {
  #private;
  constructor(matrix: BitMatrix, transform: PerspectiveTransform);
  sample(width: number, height: number): BitMatrix;
}
