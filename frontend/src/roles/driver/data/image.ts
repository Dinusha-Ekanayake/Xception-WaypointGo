// Shrinks a camera photo on the phone before it is kept and sent. A modern
// phone camera produces 5 to 12 MB; the server takes 3 MB, and a photo held for
// a day with no signal should not fill the device.

/** The server's cap (ExecutionProperties.maxAttachmentBytes). */
export const MAX_ATTACHMENT_BYTES = 3_145_728;

const LONGEST_EDGE = 1600;
const QUALITY = 0.8;

/**
 * @returns a JPEG no longer than 1600 px on its longest edge. Throws when the
 *     browser cannot decode the file, so the caller can offer the fallback
 *     reason instead of blocking the delivery (EXE-09).
 */
export async function shrink(file: Blob): Promise<Blob> {
  const bitmap = await createImageBitmap(file);
  try {
    const scale = Math.min(1, LONGEST_EDGE / Math.max(bitmap.width, bitmap.height));
    const canvas = document.createElement("canvas");
    canvas.width = Math.max(1, Math.round(bitmap.width * scale));
    canvas.height = Math.max(1, Math.round(bitmap.height * scale));
    const context = canvas.getContext("2d");
    if (!context) throw new Error("This phone cannot process the photo.");
    context.drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    return await toBlob(canvas, "image/jpeg", QUALITY);
  } finally {
    bitmap.close();
  }
}

export function toBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("This phone cannot save the image."))), type, quality);
  });
}
