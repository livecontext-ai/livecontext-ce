import { describe, it, expect } from "vitest";
import { actionErrorMessage } from "../comparisonErrors";

const FALLBACK = "fallback message";

describe("actionErrorMessage", () => {
  it("surfaces the backend reason on a 409 conflict (already reviewed by another admin)", () => {
    const err = { status: 409, message: "Publication is not pending review. Current status: ACTIVE" };
    expect(actionErrorMessage(err, FALLBACK)).toBe(
      "Publication is not pending review. Current status: ACTIVE",
    );
  });

  it("surfaces the backend reason on a 404 not-found", () => {
    const err = { status: 404, message: "Publication not found: abc" };
    expect(actionErrorMessage(err, FALLBACK)).toBe("Publication not found: abc");
  });

  it("falls back for 5xx errors (no actionable detail to expose)", () => {
    const err = { status: 500, message: "HTTP 500: Internal Server Error" };
    expect(actionErrorMessage(err, FALLBACK)).toBe(FALLBACK);
  });

  it("falls back when a 4xx carries no message", () => {
    const err = { status: 400 };
    expect(actionErrorMessage(err, FALLBACK)).toBe(FALLBACK);
  });

  it("falls back when the error has no status (network/timeout)", () => {
    expect(actionErrorMessage(new Error("network down"), FALLBACK)).toBe(FALLBACK);
  });

  it("falls back for null / non-object throwables", () => {
    expect(actionErrorMessage(null, FALLBACK)).toBe(FALLBACK);
    expect(actionErrorMessage("oops", FALLBACK)).toBe(FALLBACK);
  });
});
