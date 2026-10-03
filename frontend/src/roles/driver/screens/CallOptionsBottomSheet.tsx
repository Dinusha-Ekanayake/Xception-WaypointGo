"use client";

import { useState, useEffect, useCallback } from "react";
import { cx } from "@shared/ui";

export type ContactOption = {
  roleLabel: string;
  name: string;
  phone: string;
};

export type CallOptionsBottomSheetProps = {
  isOpen: boolean;
  onClose: () => void;
  isNight?: boolean;
  contacts?: ContactOption[];
  onCall?: (contact: ContactOption) => void;
};

const DEFAULT_CONTACTS: ContactOption[] = [
  {
    roleLabel: "Dispatcher • Peliyagoda",
    name: "Priya S.",
    phone: "+94 11 234 5678",
  },
  {
    roleLabel: "Store manager • Stop 03 Kadugannawa",
    name: "Nuwan P.",
    phone: "+94 81 257 4410",
  },
];

export default function CallOptionsBottomSheet({
  isOpen,
  onClose,
  isNight = false,
  contacts = DEFAULT_CONTACTS,
  onCall,
}: CallOptionsBottomSheetProps): React.JSX.Element | null {
  const [isRendered, setIsRendered] = useState(isOpen);
  const [isVisible, setIsVisible] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setIsRendered(true);
      const raf = requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          setIsVisible(true);
        });
      });
      return () => cancelAnimationFrame(raf);
    } else {
      setIsVisible(false);
      const timer = setTimeout(() => {
        setIsRendered(false);
      }, 300);
      return () => clearTimeout(timer);
    }
  }, [isOpen]);

  const handleClose = useCallback(() => {
    setIsVisible(false);
    setTimeout(() => {
      onClose();
    }, 300);
  }, [onClose]);

  // Close on Escape key press
  useEffect(() => {
    if (!isOpen) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        e.preventDefault();
        handleClose();
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [isOpen, handleClose]);

  if (!isRendered) return null;

  const handleCallContact = (contact: ContactOption) => {
    if (onCall) {
      onCall(contact);
    }
    // Also trigger native tel link
    window.location.href = `tel:${contact.phone.replace(/\s+/g, "")}`;
    handleClose();
  };

  return (
    <div
      role={isVisible ? "dialog" : undefined}
      aria-modal={isVisible ? "true" : undefined}
      aria-hidden={!isVisible}
      data-state={isVisible ? "open" : "closed"}
      className={cx(
        "absolute inset-0 z-50 flex flex-col justify-end overflow-hidden",
        !isVisible && "pointer-events-none"
      )}
    >
      {/* Scrim (Figma: background: rgba(0, 0, 0, 0.25); backdrop-filter: blur(6px);) */}
      <div
        className={cx(
          "absolute inset-0 bg-black/35 backdrop-blur-[6px] transition-opacity duration-300 ease-out",
          isVisible ? "opacity-100" : "opacity-0 pointer-events-none"
        )}
        onClick={handleClose}
      />

      {/* Sheet Container (Figma: auto layout, p: 20px 20px 40px, gap: 12px, h: 372px, rounded: 40px 41px 0px 0px) */}
      <div
        onClick={(e) => e.stopPropagation()}
        className={cx(
          "relative z-10 w-full rounded-t-[40px] px-5 pt-5 pb-9 flex flex-col items-center gap-3 font-go select-none shadow-2xl",
          "transition-transform duration-300 ease-[cubic-bezier(0.32,0.72,0,1)] will-change-transform",
          isVisible ? "translate-y-0" : "translate-y-full",
          isNight ? "bg-[#292929] text-white" : "bg-white text-black"
        )}
      >
        {/* Contact Cards */}
        {contacts.map((contact) => (
          <div
            key={contact.phone}
            className={cx(
              "w-full h-[100px] rounded-[22px] p-[14px_12px_14px_18px] flex items-center justify-between transition-colors",
              isNight ? "bg-[#121212]" : "bg-[#E7F3F2]"
            )}
          >
            {/* Left text column (gap: 2px) */}
            <div className="flex flex-col gap-0.5 text-left">
              <span
                className={cx(
                  "text-[13px] font-normal leading-[16px]",
                  isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
                )}
              >
                {contact.roleLabel}
              </span>
              <span
                className={cx(
                  "text-[24px] font-medium leading-[30px]",
                  isNight ? "text-white" : "text-black"
                )}
              >
                {contact.name}
              </span>
              <span
                className={cx(
                  "text-[15px] font-normal leading-[19px]",
                  isNight ? "text-[#A9A9A9]" : "text-[#6B6B6B]"
                )}
              >
                {contact.phone}
              </span>
            </div>

            {/* Right Call Action Button (w: 52px, h: 52px, rounded: 26px) */}
            <button
              type="button"
              onClick={() => handleCallContact(contact)}
              className={cx(
                "w-[52px] h-[52px] rounded-[26px] flex items-center justify-center shrink-0 transition-transform active:scale-95 shadow-sm",
                isNight
                  ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
                  : "bg-[#031A0C] text-white hover:bg-[#031A0C]/90"
              )}
              aria-label={`Call ${contact.name}`}
            >
              {/* Phone icon */}
              <svg width="22" height="22" viewBox="0 0 24 24" fill="currentColor">
                <path d="M6.62 10.79a15.053 15.053 0 006.59 6.59l2.2-2.2c.27-.27.67-.36 1.02-.24 1.12.37 2.33.57 3.57.57.55 0 1 .45 1 1V20c0 .55-.45 1-1 1-9.39 0-17-7.61-17-17 0-.55.45-1 1-1h3.5c.55 0 1 .45 1 1 0 1.25.2 2.45.57 3.57.11.35.03.74-.25 1.02l-2.2 2.2z" />
              </svg>
            </button>
          </div>
        ))}

        {/* Close Button (Figma: w: 295px, h: 64px, rounded: 22px) */}
        <button
          type="button"
          onClick={handleClose}
          className={cx(
            "w-full max-w-[295px] h-[64px] rounded-[22px] text-[20px] font-medium leading-[25px] flex items-center justify-center transition-all active:scale-[0.99] shadow-sm mt-1",
            isNight
              ? "bg-[#00BF6A] text-black hover:bg-[#00BF6A]/90"
              : "bg-[#031B08] text-white hover:bg-[#031B08]/90"
          )}
        >
          Close
        </button>
      </div>
    </div>
  );
}

