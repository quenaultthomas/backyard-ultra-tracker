/**
 * @module Base64Stream
 */
export declare const fromCharCode: (...codes: number[]) => string;
export declare class Base64Stream {
  #private;
  get bytes(): number[];
  write(byte: number): void;
  close(): void;
}
