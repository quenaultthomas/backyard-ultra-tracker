/**
 * @module index
 */
import { Charset } from '../Charset.cjs';
export interface TextEncode {
  (content: string, charset: Charset): Uint8Array;
}
export interface TextDecode {
  (bytes: Uint8Array, charset: Charset): string;
}
export declare function encode(content: string, charset: Charset): Uint8Array;
export declare function decode(bytes: Uint8Array, charset: Charset): string;
