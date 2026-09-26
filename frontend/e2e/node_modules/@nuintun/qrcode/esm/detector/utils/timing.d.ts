/**
 * @module timing
 */
import { BitMatrix } from '../../common/BitMatrix.js';
import { FinderPatternGroup } from '../FinderPatternGroup.js';
import { PerspectiveTransform } from '../../common/PerspectiveTransform.js';
export declare function checkEstimateTimingLine(
  matrix: BitMatrix,
  finderPatternGroup: FinderPatternGroup,
  isVertical?: boolean
): boolean;
export declare function checkMappingTimingLine(
  matrix: BitMatrix,
  transform: PerspectiveTransform,
  size: number,
  isVertical?: boolean
): boolean;
