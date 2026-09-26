/**
 * @module utils
 */
export type EncodingRange = [start: number, end: number];
export declare function getMappingFromCharacters(characters: string): Map<string, number>;
export declare function getMappingFromEncodingRanges(label: string, ...ranges: EncodingRange[]): Map<string, number>;
export declare function getSerialEncodinRanges(start: number, end: number, offsets: number[], delta?: number): EncodingRange[];
