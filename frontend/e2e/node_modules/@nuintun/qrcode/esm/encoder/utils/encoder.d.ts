/**
 * @module encoder
 */
import { Mode } from '../../common/Mode.js';
import { FNC1 } from '../../common/interface.js';
import { ECLevel } from '../../common/ECLevel.js';
import { BitArray } from '../../common/BitArray.js';
import { ECBlocks } from '../../common/ECBlocks.js';
import { Byte } from '../segments/Byte.js';
import { ByteMatrix } from '../../common/ByteMatrix.js';
import { Hanzi } from '../segments/Hanzi.js';
import { Kanji } from '../segments/Kanji.js';
import { Numeric } from '../segments/Numeric.js';
import { Version } from '../../common/Version.js';
import { Alphanumeric } from '../segments/Alphanumeric.js';
export interface Hints {
  fnc1?: FNC1;
}
export interface SegmentBlock {
  mode: Mode;
  head: BitArray;
  body: BitArray;
  length: number;
}
export type Segment = Alphanumeric | Byte | Hanzi | Kanji | Numeric;
export declare function injectECCodewords(bits: BitArray, { ecBlocks, numECCodewordsPerBlock }: ECBlocks): BitArray;
export declare function appendTerminator(bits: BitArray, numDataCodewords: number): void;
export declare function isByteMode(segment: Segment): segment is Byte;
export declare function isHanziMode(segment: Segment): segment is Hanzi;
export declare function appendModeInfo(bits: BitArray, mode: Mode): void;
export declare function appendECI(bits: BitArray, segment: Segment, currentECIValue: number): number;
export declare function appendFNC1Info(bits: BitArray, fnc1: FNC1): void;
export declare function getSegmentLength(segment: Segment, bits: BitArray): number;
export declare function appendLengthInfo(bits: BitArray, mode: Mode, version: Version, numLetters: number): void;
export declare function willFit(numInputBits: number, version: Version, ecLevel: ECLevel): boolean;
export declare function calculateBitsNeeded(segmentBlocks: SegmentBlock[], version: Version): number;
export declare function chooseRecommendVersion(segmentBlocks: SegmentBlock[], ecLevel: ECLevel): Version;
export declare function chooseBestMaskAndMatrix(
  codewords: BitArray,
  version: Version,
  ecLevel: ECLevel
): [mask: number, matrix: ByteMatrix];
