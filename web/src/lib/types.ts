export type TaskState = "queued" | "running" | "waiting" | "done" | "failed" | "cancelled" | "partial" | "cancelling";

export interface Step {
  round: number;
  kind: string;
  action: string;
  detail: string;
}

export interface OrbitTask {
  id?: string;
  state: TaskState | string;
  text?: string;
  steps?: Step[];
  answer?: string;
  reason?: string;
  frame?: string;
  question?: string;
  usage?: { calls: number; prompt: number; completion: number };
  hands_off?: boolean;
  on_user_display?: boolean;
  leave_app?: boolean;
  rounds?: number;
  used_op?: string;
}

export interface TaskSummary {
  state: string;
  question: string;
  answer: string;
}

export interface MapRow {
  file: string;
  dir?: string;
  app?: string;
  package?: string;
  screens?: number;
  operations?: number;
  bytes?: number;
  crawled_at?: string;
  bench?: { ops: number; exact: number; same_app: number; at: string };
  alternatives?: number;
  coverage?: Record<string, unknown>;
  modified?: number;
  pinned?: boolean;
  deletable?: boolean;
  source?: string;
  error?: string;
}

export interface Crawl {
  state: string;
  package: string;
  app: string;
  output: string;
  lines: string[];
  error?: string;
  started: number;
  finished?: number;
}

export interface AppRow {
  name?: string;
  label?: string;
  package?: string;
  has_map?: boolean;
  refusable?: boolean;
  [key: string]: unknown;
}

export interface UsageData {
  days?: number;
  total_calls?: number;
  total_prompt?: number;
  total_completion?: number;
  by_day?: { day: string; calls: number; prompt: number; completion: number }[];
  report?: string;
  [key: string]: unknown;
}
