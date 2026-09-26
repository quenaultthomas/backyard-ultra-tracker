/**
 * @module index
 */
import { BitMatrix } from '../common/BitMatrix.js';
/**
 * @function grayscale
 * @description Convert an image to grayscale.
 * @param image The image data to convert.
 */
export declare function grayscale({ data, width, height }: ImageData): Uint8Array;
/**
 * @function binarize
 * @description Convert the image to a binary matrix.
 * @param luminances The luminances of the image.
 * @param width The width of the image.
 * @param height The height of the image.
 */
export declare function binarize(luminances: Uint8Array, width: number, height: number): BitMatrix;
