/**
 * @module Mode
 */
import { Version } from './Version.js';
export declare function fromModeBits(bits: number): Mode;
export declare class Mode {
  #private;
  static readonly TERMINATOR: Mode;
  static readonly NUMERIC: Mode;
  static readonly ALPHANUMERIC: Mode;
  static readonly STRUCTURED_APPEND: Mode;
  static readonly BYTE: Mode;
  static readonly ECI: Mode;
  static readonly KANJI: Mode;
  static readonly FNC1_FIRST_POSITION: Mode;
  static readonly FNC1_SECOND_POSITION: Mode;
  static readonly HANZI: Mode;
  constructor(characterCountBitsSet: number[], bits: number);
  get bits(): number;
  getCharacterCountBits({ version }: Version): number;
}
