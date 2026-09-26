/**
 * @module asserts
 */
import { Hints } from './encoder.js';
import { Charset } from '../../common/Charset.js';
export declare function assertContent(content: string): asserts content;
export declare function assertCharset(charset: Charset): asserts charset;
export declare function assertHints(hints: Hints): asserts hints;
export declare function assertLevel(level: 'L' | 'M' | 'Q' | 'H'): asserts level;
export declare function assertVersion(version: 'Auto' | number): asserts version;
