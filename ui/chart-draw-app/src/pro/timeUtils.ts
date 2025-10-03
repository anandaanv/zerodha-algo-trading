// ui/chart-draw-app/src/pro/timeUtils.ts

export const IST_TZ = "Asia/Kolkata";

// Normalize epoch to milliseconds (accept seconds or ms)
export function normalizeToMs(time: number): number {
  return time > 1e12 ? time : time * 1000;
}

export function isIntraday(period: string | undefined): boolean {
  if (!period) return false;
  return /m|h|min|hour/i.test(period);
}

// Build a set of the earliest bar timestamp (ms) for each local day in the given TZ
export function buildFirstOfDaySet(times: Array<number | undefined | null>, tz: string = IST_TZ): Set<number> {
  const fmt = new Intl.DateTimeFormat("en-IN", {
    timeZone: tz,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  });

  const earliestPerDay: Record<string, number> = {};
  for (const t of times) {
    if (t == null) continue;
    const ms = normalizeToMs(t as number);
    const d = new Date(ms);
    const parts = fmt.formatToParts(d);
    const yyyy = parts.find(p => p.type === "year")?.value ?? "0000";
    const mm = parts.find(p => p.type === "month")?.value ?? "00";
    const dd = parts.find(p => p.type === "day")?.value ?? "00";
    const key = `${yyyy}-${mm}-${dd}`;
    if (!(key in earliestPerDay) || ms < earliestPerDay[key]) {
      earliestPerDay[key] = ms;
    }
  }
  return new Set(Object.values(earliestPerDay));
}

// Tick label formatter: date on first-of-day, time otherwise for intraday; date for higher TFs
export function formatTickMarkIST(time: number, period: string, firstOfDayMs: Set<number>, tz: string = IST_TZ): string {
  const ms = normalizeToMs(time);
  const d = new Date(ms);
  const intraday = isIntraday(period);

  const dateParts = new Intl.DateTimeFormat("en-IN", {
    timeZone: tz,
    day: "2-digit",
    month: "2-digit",
  }).formatToParts(d);
  const dd = dateParts.find(p => p.type === "day")?.value ?? "";
  const mon = dateParts.find(p => p.type === "month")?.value ?? "";

  if (intraday) {
    if (firstOfDayMs.has(ms)) {
      return `${dd}/${mon}`;
    }
    const timeParts = new Intl.DateTimeFormat("en-IN", {
      timeZone: tz,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).formatToParts(d);
    const hh = timeParts.find(p => p.type === "hour")?.value ?? "00";
    const mm = timeParts.find(p => p.type === "minute")?.value ?? "00";
    return `${hh}:${mm}`;
  }
  return `${dd}/${mon}`;
}

// Crosshair formatter: full IST date-time like "03 Oct 2025 14:23:45"
export function formatCrosshairISTFull(time: any, tz: string = IST_TZ): string {
  let ms: number;
  if (typeof time === "number") {
    ms = normalizeToMs(time);
  } else if (time && typeof time === "object" && "year" in time) {
    ms = Date.UTC(time.year, (time.month ?? 1) - 1, time.day ?? 1, 0, 0, 0);
  } else {
    ms = 0;
  }
  const d = new Date(ms);

  const dateParts = new Intl.DateTimeFormat("en-IN", {
    timeZone: tz,
    day: "2-digit",
    month: "short",
    year: "numeric",
  }).formatToParts(d);
  const dd = dateParts.find(p => p.type === "day")?.value ?? "";
  const mon = dateParts.find(p => p.type === "month")?.value ?? "";
  const yyyy = dateParts.find(p => p.type === "year")?.value ?? "";

  const timeParts = new Intl.DateTimeFormat("en-IN", {
    timeZone: tz,
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).formatToParts(d);
  const hh = timeParts.find(p => p.type === "hour")?.value ?? "00";
  const mm = timeParts.find(p => p.type === "minute")?.value ?? "00";
  const ss = timeParts.find(p => p.type === "second")?.value ?? "00";

  return `${dd} ${mon} ${yyyy} ${hh}:${mm}:${ss}`;
}
