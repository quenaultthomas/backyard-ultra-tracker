/**
 * @module DictStream
 * @see https://github.com/google/dart-gif-encoder
 */
import { Dict } from './Dict.cjs';
import { ByteStream } from '../ByteStream.cjs';
export declare class DictStream {
  #private;
  constructor(dict: Dict);
  write(code: number): void;
  pipe(stream: ByteStream): void;
}
