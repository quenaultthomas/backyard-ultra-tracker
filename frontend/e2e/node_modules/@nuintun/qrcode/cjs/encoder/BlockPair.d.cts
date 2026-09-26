/**
 * @module BlockPair
 */
export declare class BlockPair {
  #private;
  constructor(dataCodewords: Uint8Array, ecCodewords: Uint8Array);
  get ecCodewords(): Uint8Array;
  get dataCodewords(): Uint8Array;
}
