/**
 * @module Decoder
 */
import { GaloisField } from './GaloisField.js';
export declare class Decoder {
  #private;
  constructor(field?: GaloisField);
  decode(received: Int32Array, ecLength: number): number;
}
