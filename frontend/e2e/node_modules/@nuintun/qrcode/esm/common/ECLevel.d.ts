/**
 * @module ECLevel
 */
export declare const enum Level {
  L = 'L',
  M = 'M',
  Q = 'Q',
  H = 'H'
}
export declare function fromECLevelBits(bits: number): ECLevel;
export declare class ECLevel {
  #private;
  static readonly L: ECLevel;
  static readonly M: ECLevel;
  static readonly Q: ECLevel;
  static readonly H: ECLevel;
  constructor(name: `${Level}`, level: number, bits: number);
  get bits(): number;
  get level(): number;
  get name(): `${Level}`;
}
