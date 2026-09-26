/**
 * @module FormatInfo
 */
import { ECLevel } from '../common/ECLevel.js';
export declare class FormatInfo {
  #private;
  constructor(formatInfo: number);
  get mask(): number;
  get level(): ECLevel;
}
export declare function decodeFormatInfo(formatInfo1: number, formatInfo2: number): FormatInfo;
