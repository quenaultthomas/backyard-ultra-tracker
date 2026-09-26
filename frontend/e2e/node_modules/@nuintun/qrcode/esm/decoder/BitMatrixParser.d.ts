/**
 * @module BitMatrixParser
 */
import { ECLevel } from '../common/ECLevel.js';
import { BitMatrix } from '../common/BitMatrix.js';
import { FormatInfo } from './FormatInfo.js';
import { Version } from '../common/Version.js';
export declare class BitMatrixParser {
  #private;
  constructor(matrix: BitMatrix);
  readVersion(): Version;
  readFormatInfo(): FormatInfo;
  readCodewords(version: Version, ecLevel: ECLevel): Uint8Array;
  unmask(mask: number): void;
  remask(mask: number): void;
  mirror(): void;
}
