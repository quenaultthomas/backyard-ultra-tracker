/**
 * @module GridSampler
 */
import { BitMatrix } from './BitMatrix.cjs';
import { PerspectiveTransform } from './PerspectiveTransform.cjs';
export declare class GridSampler {
  #private;
  constructor(matrix: BitMatrix, transform: PerspectiveTransform);
  sample(width: number, height: number): BitMatrix;
}
