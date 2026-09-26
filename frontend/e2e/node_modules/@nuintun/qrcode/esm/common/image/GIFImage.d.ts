/**
 * @module GIFImage
 */
export type RGB = [R: number, G: number, B: number];
export interface Colors {
  foreground?: RGB;
  background?: RGB;
}
export declare class GIFImage {
  #private;
  constructor(width: number, height: number, { foreground, background }?: Colors);
  set(x: number, y: number, color: number): void;
  toDataURL(): string;
}
