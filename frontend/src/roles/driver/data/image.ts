import { encodePhoto } from "../../../shared/media/photo.ts";

// Shrinks a camera photo on the phone before it is kept and sent. A modern
// phone camera produces 5 to 12 MB; the server takes 3 MB, and a photo held for
// a day with no signal should not fill the device.

/** The server's cap (ExecutionProperties.maxAttachmentBytes). */
export const MAX_ATTACHMENT_BYTES = 3_145_728;

/**
 * @returns the photo at 1600 px on its longest edge, WebP where this phone
 *     makes it, else JPEG (roadmap M-02, shared/media/photo.ts). Throws when the
 *     browser cannot decode the file, so the caller can offer the fallback
 *     reason instead of blocking the delivery (EXE-09).
 */
export async function shrink(file: Blob): Promise<Blob> {
  return (await encodePhoto(file, MAX_ATTACHMENT_BYTES)).blob;
}

export function toBlob(canvas: HTMLCanvasElement, type: string, quality?: number): Promise<Blob> {
  return new Promise((resolve, reject) => {
    canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error("This phone cannot save the image."))), type, quality);
  });
}
