/**
 * @module Decoder
 */
import { Decoded } from './Decoded.js';
import { BitMatrix } from '../common/BitMatrix.js';
import { TextDecode } from '../common/encoding/index.js';
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
