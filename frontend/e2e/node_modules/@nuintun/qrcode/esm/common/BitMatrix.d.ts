/**
 * @module BitMatrix
 */
export declare class BitMatrix {
  #private;
  /**
   * @constructor
   * @param size The size of the square matrix.
   */
  constructor(size: number);
  /**
   * @constructor
   * @param width The width of the matrix.
   * @param height The height of the matrix.
   */
  constructor(width: number, height: number);
  /**
   * @property width
   * @description The width of the matrix.
   */
  get width(): number;
  /**
   * @property height
   * @description The height of the matrix.
   */
  get height(): number;
  /**
   * @method set
   * @description Set the bit value to 1 of the specified coordinate.
   * @param x The x coordinate.
   * @param y The y coordinate.
   */
  set(x: number, y: number): void;
  /**
   * @method get
   * @description Get the bit value of the specified coordinate.
   * @param x The x coordinate.
   * @param y The y coordinate.
   */
  get(x: number, y: number): 0 | 1;
  /**
   * @method flip
   * @description Flip the bit value of the specified coordinate.
   */
  flip(): void;
  /**
   * @method flip
   * @description Flip the bit value of the specified coordinate.
   * @param x The x coordinate.
   * @param y The y coordinate.
   */
  flip(x: number, y: number): void;
  /**
   * @method clone
   * @description Clone the bit matrix.
   */
  clone(): BitMatrix;
  /**
   * @method setRegion
   * @description Set the bit value to 1 of the specified region.
   * @param left The left coordinate.
   * @param top The top coordinate.
   * @param width The width to set.
   * @param height The height to set.
   */
  setRegion(left: number, top: number, width: number, height: number): void;
}
