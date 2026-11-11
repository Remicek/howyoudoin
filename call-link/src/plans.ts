// What each plan allows, and a person's call minutes for the month. Kept apart from the Durable Objects so the rules
// can be tested on their own.
//
// Only the HowYouDoin user who starts a call uses minutes; the person who joins from the link never pays anything. A
// call's time counts from when both are in it: as video while either camera is on, otherwise as audio.

export type Plan = "free" | "premium";

const MINUTE = 60 * 1000;
const HOUR = 60 * MINUTE;

export interface PlanLimits {
  /** Video a month. */
  videoMs: number;
  /** Audio-only time a month. */
  audioMs: number;
  /** The longest one call can last, from when both are in it. */
  maxCallMs: number;
  /** Highest video sent (pixels tall) and its bitrate cap. */
  videoHeight: number;
  videoBitrate: number;
}

export const LIMITS: Record<Plan, PlanLimits> = {
  free: { videoMs: 60 * MINUTE, audioMs: 10 * HOUR, maxCallMs: 60 * MINUTE, videoHeight: 360, videoBitrate: 350_000 },
  // "Unlimited for normal use": the caps only stop someone running calls all day
  premium: { videoMs: 50 * HOUR, audioMs: 100 * HOUR, maxCallMs: 4 * HOUR, videoHeight: 540, videoBitrate: 700_000 },
};

/** The Play product of the subscription: the only thing on sale. */
export const PREMIUM_PRODUCT = "premium_monthly";
/** How long before a call's end both people are told. */
export const WARN_BEFORE_END_MS = 5 * MINUTE;

export interface Usage {
  /** "2026-11": the month videoMs and audioMs belong to. */
  month: string;
  videoMs: number;
  audioMs: number;
}

export interface Allowance {
  plan: Plan;
  videoLeftMs: number;
  audioLeftMs: number;
  /** When the month's allowance starts again (the 1st, 00:00 UTC). */
  resetsAt: number;
}

export const monthOf = (now: number): string => new Date(now).toISOString().slice(0, 7);

export function nextMonthStart(now: number): number {
  const date = new Date(now);
  return Date.UTC(date.getUTCFullYear(), date.getUTCMonth() + 1, 1);
}

export const emptyUsage = (now: number): Usage => ({ month: monthOf(now), videoMs: 0, audioMs: 0 });

/** A new month starts the counts again. (Usage saved by older versions may carry a bought-time field: dropped.) */
export function current(usage: Usage | undefined, now: number): Usage {
  if (!usage || usage.month !== monthOf(now)) return emptyUsage(now);
  return { month: usage.month, videoMs: usage.videoMs, audioMs: usage.audioMs };
}

export function allowance(usage: Usage, plan: Plan, now: number): Allowance {
  const limits = LIMITS[plan];
  return {
    plan,
    videoLeftMs: Math.max(0, limits.videoMs - usage.videoMs),
    audioLeftMs: Math.max(0, limits.audioMs - usage.audioMs),
    resetsAt: nextMonthStart(now),
  };
}

/** Counts a call's time. */
export const charge = (usage: Usage, videoMs: number, audioMs: number): Usage => ({
  ...usage,
  videoMs: usage.videoMs + videoMs,
  audioMs: usage.audioMs + audioMs,
});
