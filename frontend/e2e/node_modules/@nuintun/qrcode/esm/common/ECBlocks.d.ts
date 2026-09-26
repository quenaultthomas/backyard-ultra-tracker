/**
 * @module ECBlocks
 */
import { ECB } from './ECB.js';
export declare class ECBlocks {
  #private;
  constructor(numECCodewordsPerBlock: number, ...ecBlocks: ECB[]);
  get ecBlocks(): ECB[];
  get numTotalCodewords(): number;
  get numTotalECCodewords(): number;
  get numTotalDataCodewords(): number;
  get numECCodewordsPerBlock(): number;
}
