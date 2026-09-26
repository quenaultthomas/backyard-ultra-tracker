/**
 * @module BitArray
 */
export declare class BitArray {
  #private;
  constructor(length?: number);
  get length(): number;
  get byteLength(): number;
  set(index: number): void;
  get(index: number): 0 | 1;
  xor(mask: BitArray): void;
  append(array: BitArray): void;
  append(value: number, length?: number): void;
  copyTo(bitOffset: number, target: Uint8Array, byteOffset: number, byteLength: number): void;
  clear(): void;
}
