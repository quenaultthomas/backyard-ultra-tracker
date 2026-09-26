/**
 * @module mask
 */
import { ByteMatrix } from './ByteMatrix.cjs';
export declare function calculateMaskPenalty(matrix: ByteMatrix): number;
export declare function isApplyMask(mask: number, x: number, y: number): boolean;
