/**
 * @module ByteMatrix
 */
export declare class ByteMatrix {
  #private;
  constructor(size: number);
  get size(): number;
  set(x: number, y: number, value: number): void;
  get(x: number, y: number): number;
  clear(value: number): void;
}
