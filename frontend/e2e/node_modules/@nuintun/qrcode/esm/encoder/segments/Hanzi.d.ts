/**
 * @module Hanzi
 */
import { Mode } from '../../common/Mode.js';
import { BitArray } from '../../common/BitArray.js';
export declare class Hanzi {
  #private;
  /**
   * @constructor
   * @param content The content to encode.
   */
  constructor(content: string);
  /**
   * @property mode
   * @description The mode of the segment.
   */
  get mode(): Mode;
  /**
   * @property content
   * @description The content of the segment.
   */
  get content(): string;
  /**
   * @method encode
   * @description Encode the segment.
   */
  encode(): BitArray;
}
