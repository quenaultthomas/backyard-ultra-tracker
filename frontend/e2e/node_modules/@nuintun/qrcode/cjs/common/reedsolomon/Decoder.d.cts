/**
 * @module Decoder
 */
import { GaloisField } from './GaloisField.cjs';
export declare class Decoder {
  #private;
  constructor(field?: GaloisField);
  decode(received: Int32Array, ecLength: number): number;
}
