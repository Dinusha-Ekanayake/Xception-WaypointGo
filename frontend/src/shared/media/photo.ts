// Roadmap M-02: one photo encoder for every role (proof of delivery, damage
// photos, receipt evidence), instead of a copy per role.
//
// A phone camera's photo is 5 to 12 MB; the server takes 3 MB, and a photo held
// for a day with no signal should not fill the phone. Each photo is redrawn at
// 1600 px on its longest side and saved as WebP, which is about a third smaller
// than JPEG at the same quality. Safari cannot encode WebP and quietly returns a
// PNG instead, so the result's type is checked and JPEG used when it is not
// WebP. Redrawing also drops the camera's EXIF data, the GPS position included.
// The server still reads the type from the bytes, never from this.

export const MAX_PHOTO_BYTES = 3 * 1024 * 1024;
export const PHOTO_SIDE = 1600;
export const THUMB_SIDE = 320;
const QUALITIES = [0.82, 0.68, 0.55];

export type Encoded = { blob: Blob; thumbnail: Blob | null };

/** The size to draw at: the longest side at most `maxSide`, never enlarged. */
export function fitWithin(width: number, height: number, maxSide: number): { width: number; height: number } {
  const scale = Math.min(1, maxSide / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** Which format a canvas really produced was asked for; a browser that cannot encode WebP gives PNG. */
export function encodedAs(asked: string, blob: Blob | null): boolean {
  return blob !== null && blob.type === asked;
}

function canvasBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob | null> {
  return new Promise((resolve) => canvas.toBlob(resolve, type, quality));
}

function draw(bitmap: ImageBitmap, maxSide: number): HTMLCanvasElement {
  const size = fitWithin(bitmap.width, bitmap.height, maxSide);
  const canvas = document.createElement("canvas");
  canvas.width = size.width;
  canvas.height = size.height;
  const context = canvas.getContext("2d");
  if (!context) throw new Error("This phone cannot process the photo.");
  context.drawImage(bitmap, 0, 0, size.width, size.height);
  return canvas;
}

/** The smallest acceptable encoding: WebP when this browser makes it, else JPEG, stepping quality down to fit. */
async function encode(canvas: HTMLCanvasElement, maxBytes: number): Promise<Blob | null> {
  for (const type of ["image/webp", "image/jpeg"]) {
    for (const quality of QUALITIES) {
      const blob = await canvasBlob(canvas, type, quality);
      if (!encodedAs(type, blob)) break; // This browser does not make this type; try the next.
      if (blob!.size <= maxBytes) return blob;
    }
  }
  return null;
}

/**
 * The photo to keep and send, and a 320 px thumbnail for lists. Throws in words
 * when the browser cannot read the file or the photo will not fit, so the
 * screen can ask for another one.
 */
export async function encodePhoto(file: Blob, maxBytes = MAX_PHOTO_BYTES): Promise<Encoded> {
  let bitmap: ImageBitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    if (file.size <= maxBytes && file.type.startsWith("image/")) return { blob: file, thumbnail: null };
    throw new Error("This phone cannot read that photo. Take it again.");
  }
  try {
    const blob = await encode(draw(bitmap, PHOTO_SIDE), maxBytes);
    if (!blob) throw new Error("This photo is too large to send. Take it again a little closer.");
    const thumbnail = await encode(draw(bitmap, THUMB_SIDE), maxBytes);
    return { blob, thumbnail };
  } finally {
    bitmap.close();
  }
}

// ---- is the photo usable? (instead of an AI photo check) -------------------

/** Below this, the photo is probably blurred; measured on a 256 px grey copy. */
export const BLUR_BELOW = 60;
/** Below this mean brightness (0-255), the photo is probably too dark to read. */
export const DARK_BELOW = 45;

export type PhotoQuality = "ok" | "blurry" | "dark";

/** Mean brightness of a grey image, 0-255. */
export function brightness(gray: ArrayLike<number>): number {
  let sum = 0;
  for (let i = 0; i < gray.length; i++) sum += gray[i]!;
  return gray.length === 0 ? 0 : sum / gray.length;
}

/**
 * Variance of the Laplacian of a grey image: high where edges are sharp, low
 * where they are smeared. The usual measure for "is this in focus".
 */
export function sharpness(gray: ArrayLike<number>, width: number, height: number): number {
  if (width < 3 || height < 3) return 0;
  let sum = 0;
  let sumSq = 0;
  let n = 0;
  for (let y = 1; y < height - 1; y++) {
    for (let x = 1; x < width - 1; x++) {
      const i = y * width + x;
      const lap = gray[i - width]! + gray[i + width]! + gray[i - 1]! + gray[i + 1]! - 4 * gray[i]!;
      sum += lap;
      sumSq += lap * lap;
      n++;
    }
  }
  const mean = sum / n;
  return sumSq / n - mean * mean;
}

export function judge(gray: ArrayLike<number>, width: number, height: number): PhotoQuality {
  if (brightness(gray) < DARK_BELOW) return "dark";
  if (sharpness(gray, width, height) < BLUR_BELOW) return "blurry";
  return "ok";
}

/** Whether a photo looks usable, judged on a small grey copy. "ok" when it cannot be judged. */
export async function checkPhoto(file: Blob): Promise<PhotoQuality> {
  try {
    const bitmap = await createImageBitmap(file);
    try {
      const canvas = draw(bitmap, 256);
      const context = canvas.getContext("2d");
      if (!context) return "ok";
      const { data } = context.getImageData(0, 0, canvas.width, canvas.height);
      const gray = new Uint8ClampedArray(canvas.width * canvas.height);
      for (let i = 0, p = 0; i < data.length; i += 4, p++) {
        gray[p] = (data[i]! * 299 + data[i + 1]! * 587 + data[i + 2]! * 114) / 1000;
      }
      return judge(gray, canvas.width, canvas.height);
    } finally {
      bitmap.close();
    }
  } catch {
    return "ok";
  }
}

/** What the screen says for a photo that does not look usable. */
export const QUALITY_HINT: Record<Exclude<PhotoQuality, "ok">, string> = {
  blurry: "This photo looks blurry. Hold the phone still and take it again.",
  dark: "This photo looks too dark. Find more light and take it again.",
};
