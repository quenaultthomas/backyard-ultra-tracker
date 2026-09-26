/**
 * @module Polynomial
 */
import { GaloisField } from './GaloisField.cjs';
export declare class Polynomial {
  #private;
  constructor(field: GaloisField, coefficients: Int32Array);
  get coefficients(): Int32Array;
  isZero(): boolean;
  getDegree(): number;
  getCoefficient(degree: number): number;
  evaluate(a: number): number;
  multiply(scalar: number): Polynomial;
  multiply(other: Polynomial): Polynomial;
  multiplyByMonomial(degree: number, coefficient: number): Polynomial;
  addOrSubtract(other: Polynomial): Polynomial;
  divide(other: Polynomial): [quotient: Polynomial, remainder: Polynomial];
}
