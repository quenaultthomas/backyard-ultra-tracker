/**
 * @module BitSource
 */
export declare class BitSource {
  #private;
  constructor(bytes: Uint8Array);
  get bitOffset(): number;
  get byteOffset(): number;
  read(length: number): number;
  available(): number;
}
