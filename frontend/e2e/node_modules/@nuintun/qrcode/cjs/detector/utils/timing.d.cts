/**
 * @module timing
 */
import { BitMatrix } from '../../common/BitMatrix.cjs';
import { FinderPatternGroup } from '../FinderPatternGroup.cjs';
import { PerspectiveTransform } from '../../common/PerspectiveTransform.cjs';
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
