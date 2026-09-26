/**
 * @module DataBlock
 */
export declare class DataBlock {
  #private;
  constructor(codewords: Uint8Array, numDataCodewords: number);
  get codewords(): Uint8Array;
  get numDataCodewords(): number;
}
