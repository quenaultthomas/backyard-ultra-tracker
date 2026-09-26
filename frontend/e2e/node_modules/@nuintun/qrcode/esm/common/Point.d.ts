/**
 * @module Point
 */
export declare class Point {
  #private;
  constructor(x: number, y: number);
  /**
   * @property x
   * @description Get the x of point.
   */
  get x(): number;
  /**
   * @property y
   * @description Get the y of point.
   */
  get y(): number;
}
export declare function distance(a: Point, b: Point): number;
export declare function squaredDistance(a: Point, b: Point): number;
export declare function calculateTriangleArea(a: Point, b: Point, c: Point): number;
