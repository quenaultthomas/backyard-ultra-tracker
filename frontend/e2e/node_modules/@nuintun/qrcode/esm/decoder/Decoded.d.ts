/**
 * @module Decoded
 */
import { FNC1 } from '../common/interface.js';
import { FormatInfo } from './FormatInfo.js';
import { Version } from '../common/Version.js';
import { Level } from '../common/ECLevel.js';
import { DecodeSource, Structured } from './utils/source.js';
export declare class Decoded {
  #private;
  constructor(metadata: DecodeSource, version: Version, { mask, level }: FormatInfo, corrected: number, mirror: boolean);
  /**
   * @property mask
   * @description Get the mask of qrcode.
   */
  get mask(): number;
  /**
   * @property level
   * @description Get the error correction level of qrcode.
   */
  get level(): `${Level}`;
  /**
   * @property version
   * @description Get the version of qrcode.
   */
  get version(): number;
  /**
   * @property mirror
   * @description Get the mirror of qrcode.
   */
  get mirror(): boolean;
  /**
   * @property content
   * @description Get the content of qrcode.
   */
  get content(): string;
  /**
   * @property corrected
   * @description Get the corrected of qrcode.
   */
  get corrected(): number;
  /**
   * @property symbology
   * @description Get the symbology of qrcode.
   */
  get symbology(): string;
  /**
   * @property fnc1
   * @description Get the fnc1 of qrcode.
   */
  get fnc1(): FNC1 | false;
  /**
   * @property codewords
   * @description Get the codewords of qrcode.
   */
  get codewords(): Uint8Array;
  /**
   * @property structured
   * @description Get the structured of qrcode.
   */
  get structured(): Structured | false;
}
