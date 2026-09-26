/**
 * @module transform
 */
import { Pattern } from '../Pattern.js';
import { FinderPatternGroup } from '../FinderPatternGroup.js';
import { PerspectiveTransform } from '../../common/PerspectiveTransform.js';
export declare function createTransform(
  finderPatternGroup: FinderPatternGroup,
  alignmentPattern?: Pattern
): PerspectiveTransform;
