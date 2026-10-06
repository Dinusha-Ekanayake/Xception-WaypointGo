import { MAX_PHOTO_BYTES, encodePhoto } from "../../../shared/media/photo.ts";

// A photo of a delivery problem, made small on this device before it is kept or
// sent (Figma "06d Damage photo"). The encoding is shared by every role
// (roadmap M-02, shared/media/photo.ts): 1600 px, WebP where the browser makes
// it, else JPEG, under the server's 3 MB, with the camera's EXIF dropped.

export { MAX_PHOTO_BYTES };

export async function shrinkPhoto(file: Blob): Promise<Blob> {
  return (await encodePhoto(file)).blob;
}
