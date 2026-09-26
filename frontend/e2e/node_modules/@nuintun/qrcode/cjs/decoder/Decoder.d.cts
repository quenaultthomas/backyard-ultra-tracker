/**
 * @module Decoder
 */
import { Decoded } from './Decoded.cjs';
import { BitMatrix } from '../common/BitMatrix.cjs';
import { TextDecode } from '../common/encoding/index.cjs';
export interface Options {
  /**
   * @property decode
   * @description Text decode function.
   */
  decode?: TextDecode;
}
export declare class Decoder {
  #private;
  /**
   * @constructor
   * @param options The options of decoder.
   */
  constructor({ decode }?: Options);
  /**
   * @method decode
   * @description Decode the qrcode matrix.
   * @param matrix The qrcode matrix.
   */
  decode(matrix: BitMatrix): Decoded;
}
