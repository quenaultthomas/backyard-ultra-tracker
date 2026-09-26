/**
 * @module decoder
 */
import { ECLevel } from '../../common/ECLevel.cjs';
import { Version } from '../../common/Version.cjs';
import { DataBlock } from '../DataBlock.cjs';
export declare function correctErrors(
  codewords: Uint8Array,
  numDataCodewords: number
): [codewords: Int32Array, errorsCorrected: number];
export declare function getDataBlocks(codewords: Uint8Array, version: Version, ecLevel: ECLevel): DataBlock[];
