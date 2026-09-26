/**
 * @module source
 */
import { FNC1 } from '../../common/interface.js';
import { Version } from '../../common/Version.js';
import { TextDecode } from '../../common/encoding/index.js';
export interface Structured {
  readonly index: number;
  readonly count: number;
  readonly parity: number;
}
export interface DecodeSource {
  readonly content: string;
  readonly symbology: string;
  readonly fnc1: FNC1 | false;
  readonly codewords: Uint8Array;
  readonly structured: Structured | false;
}
export declare function decode(codewords: Uint8Array, version: Version, decode: TextDecode): DecodeSource;
