"use client";
import { useState, useEffect } from "react";
import { useShell, cx } from "@shared/ui";
import { useInbox } from "@shared/notifications/useInbox";
import HomeNoVehicle from "./screens/HomeNoVehicle.tsx";
import DriverLoginScreen from "./screens/DriverLoginScreen.tsx";
import GetVehicleCameraAccess from "./screens/GetVehicleCameraAccess.tsx";
import EnterVehicleId from "./screens/EnterVehicleId.tsx";
import RouteNextStop from "./screens/RouteNextStop.tsx";
import RouteMapScreen from "./screens/RouteMapScreen.tsx";
import DeliveryReportWaiting from "./screens/DeliveryReportWaiting.tsx";
import RunCompleteScreen from "./screens/RunCompleteScreen.tsx";
import NoTripPlanScreen from "./screens/NoTripPlanScreen.tsx";
import DrivingModeScreen from "./screens/DrivingModeScreen.tsx";
import RouteChangedBottomSheet from "./screens/RouteChangedBottomSheet.tsx";
import SignOutConfirmBottomSheet from "./screens/SignOutConfirmBottomSheet.tsx";
import { DriverMorphHeader, type SupportedLang } from "./ui.tsx";
import { ROUTE_STOPS } from "./screens/routeData.ts";

export type DriverProps = {
  userId?: string;
  displayName?: string;
  scope?: string[];
};

/**
 * Driver workspace designed strictly for mobile viewports (Figma node 6-37).
 * On desktop or wide screens, it preserves the exact mobile view centered
 * in a phone frame (max-w-[393px]), without expanding into a desktop layout.
 */
export default function Driver({
  userId,
  displayName = "Rashmika Dilshan",
  scope = [],
}: DriverProps): React.JSX.Element {
  const shell = useShell();
  // The driver's notifications (issue #118): the Home feed and the driving-mode badge.
  const inbox = useInbox(userId ?? null, Boolean(userId));
  const [isNight, setIsNight] = useState(false);
  const [lang, setLang] = useState<SupportedLang>("en");
  const [showSignOutConfirm, setShowSignOutConfirm] = useState(false);
  const [currentScreen, setCurrentScreen] = useState<
    "login" | "home" | "camera-access" | "enter-id" | "route-next-stop" | "route-map" | "delivery-waiting" | "run-complete" | "no-trip-plan"
  >("login");
  const [currentStopIndex, setCurrentStopIndex] = useState<number>(0);
  const [assignedVehicle, setAssignedVehicle] = useState<string | null>(null);
  const [staffId, setStaffId] = useState<string>("DRV-00021");
  const [showRouteChangedModal, setShowRouteChangedModal] = useState(false);
  const [isDrivingMode, setIsDrivingMode] = useState<boolean>(false);
  const [isDrivingRendered, setIsDrivingRendered] = useState<boolean>(false);
  const [isDrivingVisible, setIsDrivingVisible] = useState<boolean>(false);
  const [tripStatus, setTripStatus] = useState<"not-started" | "in-progress" | "completed">("not-started");
  const isGetVehicleFlow = currentScreen === "camera-access" || currentScreen === "enter-id";
  const isPersistentHeader = currentScreen === "home" || currentScreen === "route-next-stop" || currentScreen === "route-map";

  // 0.5s fade-in and 0.5s fade-out animation orchestration for Driving Mode
  useEffect(() => {
    if (isDrivingMode) {
      setIsDrivingRendered(true);
      const raf = requestAnimationFrame(() => {
        requestAnimationFrame(() => {
          setIsDrivingVisible(true);
        });
      });
      return () => cancelAnimationFrame(raf);
    } else {
      setIsDrivingVisible(false);
      const timer = setTimeout(() => {
        setIsDrivingRendered(false);
      }, 500);
      return () => clearTimeout(timer);
    }
  }, [isDrivingMode]);

  const handleStartTrip = () => {
    if (tripStatus !== "in-progress") {
      setCurrentStopIndex(0);
      setTripStatus("in-progress");
    }
    setCurrentScreen("route-next-stop");
  };

  // Press SPACEBAR to enter Driving mode (cannot be dismissed via Spacebar; only via slider)
  // 'm' for Route changed, 'n' for No trip plan, 'c' for Run complete demo
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) {
        return;
      }

      if (e.code === "Space" || e.key === " ") {
        e.preventDefault();
        // Space opens Driving Mode only; it cannot disappear from Space
        setIsDrivingMode(true);
      } else if (isDrivingMode) {
        // While Driving Mode is active, block background demo shortcuts
        return;
      } else if (e.key === "m" || e.key === "M") {
        e.preventDefault();
        setShowRouteChangedModal((prev) => !prev);
      } else if (e.key === "n" || e.key === "N") {
        e.preventDefault();
        setTripStatus("completed");
        setCurrentScreen("no-trip-plan");
      } else if (e.key === "c" || e.key === "C") {
        e.preventDefault();
        setTripStatus("completed");
        setCurrentScreen("run-complete");
      }
    };

    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [isDrivingMode]);

  const handleVehicleAssigned = (vehicleId: string) => {
    setAssignedVehicle(vehicleId);
    setCurrentScreen("home");
  };

  return (
    <main
      aria-label="Driver workspace"
      className={cx(
        "h-dvh max-h-dvh sm:h-auto sm:min-h-dvh w-full sm:py-6 flex items-center justify-center font-go select-none overflow-hidden transition-colors",
        isNight ? "bg-[#161616] sm:bg-[#0a0a0a]" : "bg-[#E7F3F2] sm:bg-[#d6e7e5]"
      )}
    >
      <div
        className={cx(
          "w-full h-full sm:max-w-[393px] h-dvh sm:h-[852px] sm:max-h-[852px] sm:rounded-[44px] sm:shadow-2xl overflow-hidden relative transition-colors",
          isNight ? "bg-[#161616]" : "bg-[#E7F3F2]"
        )}
      >
        {/* Screen 0: Login Screen */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "login"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "-translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <DriverLoginScreen
            onLoginSuccess={(id) => {
              if (id) setStaffId(id);
              setCurrentScreen("home");
            }}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            defaultStaffId=""
          />
        </div>

        {/* Persistent Morphing Header for Home, RouteNextStop, and RouteMap screens */}
        <div
          className={cx(
            "driver-header-container absolute top-0 inset-x-0 z-30 transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            isPersistentHeader
              ? "translate-x-0 opacity-100 pointer-events-auto"
              : currentScreen === "login"
                ? "translate-x-full opacity-0 pointer-events-none"
                : "-translate-x-full opacity-0 pointer-events-none"
          )}
        >
          <DriverMorphHeader
            activeScreen={
              currentScreen === "route-map"
                ? "route-map"
                : currentScreen === "route-next-stop"
                  ? "route-next-stop"
                  : "home"
            }
            onBack={() => {
              if (currentScreen === "route-map") {
                setCurrentScreen("route-next-stop");
              } else {
                setCurrentScreen("home");
              }
            }}
            lang={lang}
            onToggleLang={setLang}
            onSignOut={() => setShowSignOutConfirm(true)}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            isNight={isNight}
          />
        </div>

        {/* Screen 1: Home - slides in from login and slides left on forward navigation */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "home"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : currentScreen === "login"
                ? "translate-x-full opacity-0 pointer-events-none z-0"
                : "-translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <HomeNoVehicle
            driverName={displayName || "Rashmika Dilshan"}
            driverCode={staffId || "DRV-00021"}
            depotName="Kandy depot"
            assignedVehicle={assignedVehicle}
            tripStatus={tripStatus}
            onGetVehicle={() => setCurrentScreen("camera-access")}
            onStartRun={handleStartTrip}
            onStartTrip={handleStartTrip}
            onSignOut={() => {
              setTripStatus("not-started");
              setCurrentStopIndex(0);
              setCurrentScreen("login");
              shell?.onSignOut?.();
            }}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            hideHeader={isPersistentHeader}
            inbox={userId ? inbox : undefined}
          />
        </div>

        {/* Get Vehicle Flow (Camera Access & Manual ID) with persistent stationary header */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)] flex flex-col font-go select-none overflow-hidden",
            isGetVehicleFlow
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0",
            isNight ? "bg-[#161616] text-white" : "bg-[#E7F3F2] text-black"
          )}
        >
          {/* Persistent Header: Back button + "Get vehicle" step label */}
          <div className="pt-[27px] px-[43px] shrink-0 z-20">
            <div className="flex items-center justify-between">
              <button
                type="button"
                onClick={() => setCurrentScreen("home")}
                className={cx(
                  "flex items-center gap-[9px] text-[20px] font-medium leading-[25px] h-[30px] transition-opacity active:opacity-70",
                  isNight ? "text-white" : "text-black"
                )}
                aria-label="Go back"
              >
                <svg width="9" height="14" viewBox="0 0 9 14" fill="none">
                  <path
                    d="M7.75 1L1.25 7L7.75 13"
                    stroke="currentColor"
                    strokeWidth="2.2"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
                <span>Back</span>
              </button>
              <span
                className={cx(
                  "text-[15px] font-light leading-[19px] text-right",
                  isNight ? "text-white" : "text-black"
                )}
              >
                Get vehicle
              </span>
            </div>
          </div>

          {/* Sliding sub-screens: slide beneath the persistent header */}
          <div className="relative flex-1 w-full overflow-hidden">
            {/* Screen 2: Get Vehicle (Camera Access) */}
            <div
              className={cx(
                "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
                currentScreen === "enter-id"
                  ? "-translate-x-full opacity-0 pointer-events-none z-0"
                  : "translate-x-0 opacity-100 pointer-events-auto z-10"
              )}
            >
              <GetVehicleCameraAccess
                isActive={currentScreen === "camera-access"}
                onBack={() => setCurrentScreen("home")}
                onScanSuccess={handleVehicleAssigned}
                onEnterManualId={() => setCurrentScreen("enter-id")}
                isNight={isNight}
                hideHeader
              />
            </div>

            {/* Screen 3: Enter vehicle ID manually */}
            <div
              className={cx(
                "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
                currentScreen === "enter-id"
                  ? "translate-x-0 opacity-100 pointer-events-auto z-10"
                  : "translate-x-full opacity-0 pointer-events-none z-0"
              )}
            >
              <EnterVehicleId
                onBack={() => setCurrentScreen("home")}
                onScanQrInstead={() => setCurrentScreen("camera-access")}
                onContinue={handleVehicleAssigned}
                depotName="Kandy depot"
                isNight={isNight}
                hideHeader
              />
            </div>
          </div>
        </div>

        {/* Screen 4: Route Next Stop - slides in from right when Start run is clicked */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "route-next-stop"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <RouteNextStop
            stopIndex={currentStopIndex}
            onSelectStop={(idx) => setCurrentStopIndex(idx)}
            onBack={() => setCurrentScreen("home")}
            onOpenMap={() => setCurrentScreen("route-map")}
            onArrived={() => setCurrentScreen("delivery-waiting")}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            hideHeader={isPersistentHeader}
          />
        </div>

        {/* Screen 5: Google Maps API UI View - opens when Map button is tapped */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "route-map"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <RouteMapScreen
            onBack={() => setCurrentScreen("route-next-stop")}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            hideHeader={isPersistentHeader}
          />
        </div>

        {/* Screen 6: Delivery report Waiting for store - opens when I've arrived is clicked */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "delivery-waiting"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <DeliveryReportWaiting
            stopIndex={currentStopIndex}
            onBack={() => setCurrentScreen("route-next-stop")}
            onConfirmSuccess={() => {
              if (currentStopIndex >= ROUTE_STOPS.length - 1) {
                // All stops completed -> route loop finished
                setTripStatus("completed");
                setCurrentScreen("no-trip-plan");
              } else {
                setCurrentStopIndex((prev) => prev + 1);
                setCurrentScreen("route-next-stop");
              }
            }}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
          />
        </div>

        {/* Screen 7: Run complete screen */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "run-complete"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <RunCompleteScreen
            onBack={() => {
              if (tripStatus === "completed") {
                setCurrentScreen("home");
              } else {
                setCurrentScreen("route-next-stop");
              }
            }}
            onBackToHome={() => {
              setTripStatus("completed");
              setCurrentScreen("no-trip-plan");
            }}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            vehicleId={assignedVehicle ?? "VEH043"}
            depotName="Kandy depot"
          />
        </div>

        {/* Screen 8: No trip plan screen - opens after confirming the last stop of the trip */}
        <div
          className={cx(
            "absolute inset-0 w-full h-full transition-all duration-350 ease-[cubic-bezier(0.25,1,0.5,1)]",
            currentScreen === "no-trip-plan"
              ? "translate-x-0 opacity-100 pointer-events-auto z-10"
              : "translate-x-full opacity-0 pointer-events-none z-0"
          )}
        >
          <NoTripPlanScreen
            onBack={() => {
              if (tripStatus === "completed") {
                setCurrentScreen("home");
              } else {
                setCurrentScreen("route-next-stop");
              }
            }}
            onBackToHome={() => {
              setCurrentStopIndex(0);
              setTripStatus("completed");
              setCurrentScreen("home");
            }}
            isNight={isNight}
            onToggleTheme={() => setIsNight((prev) => !prev)}
            vehicleId={assignedVehicle ?? "VEH043"}
            depotName="Kandy depot"
          />
        </div>

        {/* Sign Out Confirmation Modal */}
        <SignOutConfirmBottomSheet
          isOpen={showSignOutConfirm}
          onClose={() => setShowSignOutConfirm(false)}
          onConfirm={() => {
            setShowSignOutConfirm(false);
            setTripStatus("not-started");
            setCurrentStopIndex(0);
            setCurrentScreen("login");
            shell?.onSignOut?.();
          }}
          isNight={isNight}
        />

        {/* Route Changed Message Bottom Sheet (triggered by 'm' or dispatcher event) */}
        <RouteChangedBottomSheet
          isOpen={showRouteChangedModal}
          onClose={() => setShowRouteChangedModal(false)}
          onViewRoute={() => {
            setShowRouteChangedModal(false);
            setCurrentScreen("route-next-stop");
          }}
          isNight={isNight}
        />

        {/* Driving Mode Overlay - 0.5s fade in and 0.5s fade out */}
        {isDrivingRendered && (
          <div
            className={cx(
              "absolute inset-0 z-50 transition-opacity duration-500 ease-in-out",
              isDrivingVisible
                ? "opacity-100 pointer-events-auto"
                : "opacity-0 pointer-events-none"
            )}
          >
            <DrivingModeScreen
              onExit={() => setIsDrivingMode(false)}
              isNight={isNight}
              stopIndex={currentStopIndex}
              onToggleTheme={() => setIsNight((prev) => !prev)}
              unread={userId ? inbox.unread : undefined}
            />
          </div>
        )}
      </div>
    </main>
  );
}


