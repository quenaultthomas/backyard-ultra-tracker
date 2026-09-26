/**
 * @module PerspectiveTransform
 */
export declare class PerspectiveTransform {
  #private;
  constructor(
    a11: number,
    a21: number,
    a31: number,
    a12: number,
    a22: number,
    a32: number,
    a13: number,
    a23: number,
    a33: number
  );
  inverse(): PerspectiveTransform;
  times(other: PerspectiveTransform): PerspectiveTransform;
  mapping(x: number, y: number): [x: number, y: number];
}
export declare function quadrilateralToQuadrilateral(
  x0: number,
  y0: number,
  x1: number,
  y1: number,
  x2: number,
  y2: number,
  x3: number,
  y3: number,
  x0p: number,
  y0p: number,
  x1p: number,
  y1p: number,
  x2p: number,
  y2p: number,
  x3p: number,
  y3p: number
): PerspectiveTransform;
