/**
 * @module module
 */
import { Pattern } from '../Pattern.js';
import { BitMatrix } from '../../common/BitMatrix.js';
export type ModuleSizeGroup = readonly [x: number, y: number];
export declare function calculateModuleSizeOneWay(matrix: BitMatrix, pattern1: Pattern, pattern2: Pattern): number;
