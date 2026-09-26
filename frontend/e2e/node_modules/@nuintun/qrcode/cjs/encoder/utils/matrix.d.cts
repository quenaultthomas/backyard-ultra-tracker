/**
 * @module matrix
 */
import { ECLevel } from '../../common/ECLevel.cjs';
import { BitArray } from '../../common/BitArray.cjs';
import { ByteMatrix } from '../../common/ByteMatrix.cjs';
import { Version } from '../../common/Version.cjs';
export declare function buildMatrix(codewords: BitArray, version: Version, ecLevel: ECLevel, mask: number): ByteMatrix;
