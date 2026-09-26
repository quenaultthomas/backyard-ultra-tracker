/**
 * @module decoder
 */
import { ECLevel } from '../../common/ECLevel.js';
import { Version } from '../../common/Version.js';
import { DataBlock } from '../DataBlock.js';
export declare function correctErrors(
  codewords: Uint8Array,
  numDataCodewords: number
): [codewords: Int32Array, errorsCorrected: number];
export declare function getDataBlocks(codewords: Uint8Array, version: Version, ecLevel: ECLevel): DataBlock[];
