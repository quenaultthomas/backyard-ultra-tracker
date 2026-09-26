/**
 * @module Dict
 * @see https://github.com/google/dart-gif-encoder
 */
/**
 * A dict contains codes defined during LZW compression. It's a mapping from a string
 * of pixels to the code that represents it. The codes are stored in a trie which is
 * represented as a map. Codes may be up to 12 bits. The size of the codebook is always
 * the minimum power of 2 needed to represent all the codes and automatically increases
 * as new codes are defined.
 */
export declare class Dict {
  #private;
  constructor(depth: number);
  get bof(): number;
  get eof(): number;
  get bits(): number;
  get depth(): number;
  reset(): void;
  add(code: number, index: number): boolean;
  get(code: number, index: number): number | undefined;
}
