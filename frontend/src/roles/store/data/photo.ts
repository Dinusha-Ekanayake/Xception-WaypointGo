// A photo of a delivery problem, made small on this device before it is kept or
// sent (Figma "06d Damage photo"). A phone camera's photo is several megabytes;
// the server takes 3 MB at most, and a counter on a weak connection should not
// wait for more than it needs. JPEG, longest side 1600 px, which still shows a
// torn carton or a label clearly.

export const MAX_PHOTO_BYTES = 3 * 1024 * 1024;

export async function shrinkPhoto(file: Blob, maxSide = 1600): Promise<Blob> {
  try {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxSide / Math.max(bitmap.width, bitmap.height));
    const width = Math.max(1, Math.round(bitmap.width * scale));
    const height = Math.max(1, Math.round(bitmap.height * scale));
    const canvas = document.createElement("canvas");
    canvas.width = width;
    canvas.height = height;
    canvas.getContext("2d")?.drawImage(bitmap, 0, 0, width, height);
    bitmap.close();
    for (const quality of [0.82, 0.65, 0.5]) {
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, "image/jpeg", quality));
      if (blob && blob.size <= MAX_PHOTO_BYTES) return blob;
    }
  } catch {
    // Not an image this browser can draw; the original may still be small enough.
  }
  if (file.size <= MAX_PHOTO_BYTES) return file;
  throw new Error("This photo is too large to send. Take it again a little closer.");
}
