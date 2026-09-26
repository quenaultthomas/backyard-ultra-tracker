/**
 * @module matrix
 */
import { ECLevel } from '../../common/ECLevel.js';
import { BitArray } from '../../common/BitArray.js';
import { ByteMatrix } from '../../common/ByteMatrix.js';
import { Version } from '../../common/Version.js';
export declare function buildMatrix(codewords: BitArray, version: Version, ecLevel: ECLevel, mask: number): ByteMatrix;
