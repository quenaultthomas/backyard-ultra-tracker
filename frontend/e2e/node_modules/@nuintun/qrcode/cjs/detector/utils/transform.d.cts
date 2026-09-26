/**
 * @module transform
 */
import { Pattern } from '../Pattern.cjs';
import { FinderPatternGroup } from '../FinderPatternGroup.cjs';
import { PerspectiveTransform } from '../../common/PerspectiveTransform.cjs';
export declare function createTransform(
  finderPatternGroup: FinderPatternGroup,
  alignmentPattern?: Pattern
): PerspectiveTransform;
