/**
 * @module Encoder
 */
import { GaloisField } from './GaloisField.cjs';
export declare class Encoder {
  #private;
  constructor(field?: GaloisField);
  encode(received: Int32Array, ecLength: number): void;
}
