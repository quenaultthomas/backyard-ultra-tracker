/**
 * @module module
 */
import { Pattern } from '../Pattern.cjs';
import { BitMatrix } from '../../common/BitMatrix.cjs';
export type ModuleSizeGroup = readonly [x: number, y: number];
export declare function calculateModuleSizeOneWay(matrix: BitMatrix, pattern1: Pattern, pattern2: Pattern): number;
