import { Button, Modal } from "../../ui.tsx";
import { useT } from "../../i18n.tsx";

// "06d Damage photo": the manager checks the photo is useful before going on.
// Retake throws it away and opens the camera again; Close keeps it.

/** @param hint M-02: said when the photo looks blurry or dark; the photo can still be kept. */
export default function PhotoDialog({ url, hint, onRetake, onClose }: { url: string; hint?: string | null; onRetake: () => void; onClose: () => void }): React.JSX.Element {
  const t = useT();
  return (
    <Modal label={t("Product photo")} onClose={onClose}>
      <h2 className="text-[22px] font-medium text-black">{t("Product photo")}</h2>
      {/* A local object URL of a photo just taken; nothing for next/image to optimise. */}
      <img src={url} alt={t("The photo of the problem")} decoding="async" className="max-h-[50dvh] w-full rounded-[16px] object-contain bg-go-canvas" />
      {hint && <p role="status" className="text-[14px] text-go-warning-text">{hint}</p>}
      <div className="flex gap-2.5">
        <Button tone="plain" large onClick={onRetake}>
          {t("Retake")}
        </Button>
        <Button large onClick={onClose}>
          {t("Close")}
        </Button>
      </div>
    </Modal>
  );
}
