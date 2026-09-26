/**
 * @module BitMatrixParser
 */
import { ECLevel } from '../common/ECLevel.cjs';
import { BitMatrix } from '../common/BitMatrix.cjs';
import { FormatInfo } from './FormatInfo.cjs';
import { Version } from '../common/Version.cjs';
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
