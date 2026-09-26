/**
 * @module Charset
 */
export declare function fromCharsetValue(value: number): Charset;
export declare class Charset {
  #private;
  static readonly CP437: Charset;
  static readonly ISO_8859_1: Charset;
  static readonly ISO_8859_2: Charset;
  static readonly ISO_8859_3: Charset;
  static readonly ISO_8859_4: Charset;
  static readonly ISO_8859_5: Charset;
  static readonly ISO_8859_6: Charset;
  static readonly ISO_8859_7: Charset;
  static readonly ISO_8859_8: Charset;
  static readonly ISO_8859_9: Charset;
  static readonly ISO_8859_10: Charset;
  static readonly ISO_8859_11: Charset;
  static readonly ISO_8859_13: Charset;
  static readonly ISO_8859_14: Charset;
  static readonly ISO_8859_15: Charset;
  static readonly ISO_8859_16: Charset;
  static readonly SHIFT_JIS: Charset;
  static readonly CP1250: Charset;
  static readonly CP1251: Charset;
  static readonly CP1252: Charset;
  static readonly CP1256: Charset;
  static readonly UTF_16BE: Charset;
  static readonly UTF_8: Charset;
  static readonly ASCII: Charset;
  static readonly BIG5: Charset;
  static readonly GB2312: Charset;
  static readonly EUC_KR: Charset;
  static readonly GBK: Charset;
  static readonly GB18030: Charset;
  static readonly UTF_16LE: Charset;
  static readonly UTF_32BE: Charset;
  static readonly UTF_32LE: Charset;
  static readonly ISO_646_INV: Charset;
  static readonly BINARY: Charset;
  /**
   * @constructor
   * @param label The label of charset.
   * @param values The values of charset.
   */
  constructor(label: string, ...values: number[]);
  /**
   * @property label
   * @description Get the label of charset.
   */
  get label(): string;
  /**
   * @property values
   * @description Get the values of charset.
   */
  get values(): readonly number[];
}
