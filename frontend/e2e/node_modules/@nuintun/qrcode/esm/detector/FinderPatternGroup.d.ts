/**
 * @module FinderPatternGroup
 */
import { Pattern } from './Pattern.js';
import { BitMatrix } from '../common/BitMatrix.js';
import { ModuleSizeGroup } from './utils/module.js';
import { Point } from '../common/Point.js';
export declare class FinderPatternGroup {
  #private;
  static moduleSizes(finderPatternGroup: FinderPatternGroup): ModuleSizeGroup;
  static size(finderPatternGroup: FinderPatternGroup): number;
  static moduleSize(finderPatternGroup: FinderPatternGroup): number;
  static contains(finderPatternGroup: FinderPatternGroup, pattern: Pattern): boolean;
  static bottomRight(finderPatternGroup: FinderPatternGroup): Point;
  constructor(matrix: BitMatrix, patterns: Pattern[]);
  get topLeft(): Pattern;
  get topRight(): Pattern;
  get bottomLeft(): Pattern;
}
export declare function calculateTopLeftAngle({ topLeft, topRight, bottomLeft }: FinderPatternGroup): number;
