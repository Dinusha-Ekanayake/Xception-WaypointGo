import { Button, Modal } from "../../ui.tsx";

// "06d Damage photo": the manager checks the photo is useful before going on.
// Retake throws it away and opens the camera again; Close keeps it.

export default function PhotoDialog({ url, onRetake, onClose }: { url: string; onRetake: () => void; onClose: () => void }): React.JSX.Element {
  return (
    <Modal label="Product photo" onClose={onClose}>
      <h2 className="text-[22px] font-medium text-black">Product photo</h2>
      {/* A local object URL of a photo just taken; nothing for next/image to optimise. */}
      <img src={url} alt="The photo of the problem" className="max-h-[50dvh] w-full rounded-[16px] object-contain bg-go-canvas" />
      <div className="flex gap-2.5">
        <Button tone="plain" large onClick={onRetake}>
          Retake
        </Button>
        <Button large onClick={onClose}>
          Close
        </Button>
      </div>
    </Modal>
  );
}
