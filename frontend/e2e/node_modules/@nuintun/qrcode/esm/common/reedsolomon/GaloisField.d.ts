/**
 * @module GaloisField
 */
import { Polynomial } from './Polynomial.js';
export declare class GaloisField {
  #private;
  constructor(primitive: number, size: number, generator: number);
  get size(): number;
  get one(): Polynomial;
  get zero(): Polynomial;
  get generator(): number;
  exp(a: number): number;
  log(a: number): number;
  invert(a: number): number;
  multiply(a: number, b: number): number;
  buildPolynomial(degree: number, coefficient: number): Polynomial;
}
export declare const QR_CODE_FIELD_256: GaloisField;
