import assert from "node:assert/strict";
import test from "node:test";
import { installKind } from "../src/shared/pwa/install.ts";

// What "Install app" in settings offers on each device (issue #201).

const IPHONE_SAFARI = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1";
const IPHONE_CHROME = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/130.0 Mobile/15E148 Safari/604.1";
const IPAD_SAFARI = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15";
const ANDROID_CHROME = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36";

test("an installed app offers nothing, whatever the browser", () => {
  assert.equal(installKind({ standalone: true, prompt: true, userAgent: ANDROID_CHROME, touchPoints: 5 }), "installed");
  assert.equal(installKind({ standalone: true, prompt: false, userAgent: IPHONE_SAFARI, touchPoints: 5 }), "installed");
});

test("Chrome on Android offers its own prompt once it has fired", () => {
  assert.equal(installKind({ standalone: false, prompt: true, userAgent: ANDROID_CHROME, touchPoints: 5 }), "prompt");
  assert.equal(installKind({ standalone: false, prompt: false, userAgent: ANDROID_CHROME, touchPoints: 5 }), "none");
});

test("Safari on iPhone and iPad gets the Add to Home Screen steps", () => {
  assert.equal(installKind({ standalone: false, prompt: false, userAgent: IPHONE_SAFARI, touchPoints: 5 }), "ios");
  assert.equal(installKind({ standalone: false, prompt: false, userAgent: IPAD_SAFARI, touchPoints: 5 }), "ios");
});

test("a Mac, and Chrome on iPhone, which cannot add to the home screen, get nothing", () => {
  assert.equal(installKind({ standalone: false, prompt: false, userAgent: IPAD_SAFARI, touchPoints: 0 }), "none");
  assert.equal(installKind({ standalone: false, prompt: false, userAgent: IPHONE_CHROME, touchPoints: 5 }), "none");
});
