/**
 * @module PatternRatios
 */
export declare class PatternRatios {
  #private;
  constructor(ratios: number[]);
  get modules(): number;
  get ratios(): number[];
}
export declare const FINDER_PATTERN_RATIOS: PatternRatios;
export declare const ALIGNMENT_PATTERN_RATIOS: PatternRatios;
export declare const ALIGNMENT_PATTERN_LOOSE_MODE_RATIOS: PatternRatios;
