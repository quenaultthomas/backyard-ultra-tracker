/**
 * @module encoder
 */
import { Mode } from '../../common/Mode.cjs';
import { FNC1 } from '../../common/interface.cjs';
import { ECLevel } from '../../common/ECLevel.cjs';
import { BitArray } from '../../common/BitArray.cjs';
import { ECBlocks } from '../../common/ECBlocks.cjs';
import { Byte } from '../segments/Byte.cjs';
import { ByteMatrix } from '../../common/ByteMatrix.cjs';
import { Hanzi } from '../segments/Hanzi.cjs';
import { Kanji } from '../segments/Kanji.cjs';
import { Numeric } from '../segments/Numeric.cjs';
import { Version } from '../../common/Version.cjs';
import { Alphanumeric } from '../segments/Alphanumeric.cjs';
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
