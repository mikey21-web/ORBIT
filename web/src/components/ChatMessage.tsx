import { useState } from "react";
import { ChevronDown, ChevronUp, Image as ImageIcon, Square } from "lucide-react";
import type { Step } from "../lib/types";

export interface ChatMessage {
  id: string;
  role: "user" | "agent";
  text: string;
  time: number;
  taskId?: string;
  steps?: Step[];
  state?: string;
  question?: string;
  frame?: string;
  usage?: { calls: number; prompt: number; completion: number };
  stopped?: boolean;
}

const TERMINAL = ["done", "failed", "cancelled", "partial", "cancelling"];

function timeLabel(ts: number) {
  return new Date(ts).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

export function ChatMessageView({ message, onCancel }: { message: ChatMessage; onCancel: (id: string) => void }) {
  const [open, setOpen] = useState(false);
  const working = message.role === "agent" && !!message.taskId && !TERMINAL.includes(message.state || "");
  const last = message.steps?.[message.steps.length - 1];
  const steps = message.steps || [];

  if (message.role === "user") {
    return (
      <div className="fade-in flex justify-end gap-2 px-4 py-2 sm:px-0">
        <div className="max-w-[82%] sm:max-w-[70%]">
          <div className="rounded-2xl rounded-br-md border border-persimmon/25 bg-[#f6e3da] px-4 py-2.5 text-[15px] leading-relaxed text-[#33261d] shadow-sm shadow-black/5">
            {message.text}
          </div>
          <div className="mt-1 pr-1 text-right font-mono text-[9px] text-faint">{timeLabel(message.time)}</div>
        </div>
      </div>
    );
  }

  return (
    <div className="fade-in flex justify-start gap-3 px-4 py-2 sm:px-0">
      <img src="./orbit-icon.png" alt="Orbit" className="mt-1 h-7 w-7 shrink-0 rounded-lg" />
      <div className="max-w-[86%] sm:max-w-[76%]">
        <div className="rounded-2xl rounded-tl-md border border-line bg-surface px-4 py-3 text-[15px] leading-relaxed text-linen shadow-sm shadow-black/5">
          {message.frame && (
            <div className="mb-3 overflow-hidden rounded-lg border border-line bg-black/50">
              <img src={`data:image/png;base64,${message.frame}`} alt="Phone screen" className="max-h-64 w-full object-contain" />
            </div>
          )}

          {message.question && working && (
            <div className="mb-2 rounded-lg border border-persimmon/40 bg-persimmon/5 px-3 py-2 text-sm text-linen">
              {message.question}
            </div>
          )}

          {working ? (
            <>
              <div className="flex items-center gap-2">
                <span className="typing"><span /><span /><span /></span>
                <span className="text-xs text-muted">
                  {last ? <>{last.kind} · {last.action}{last.detail ? ` — ${last.detail.slice(0, 70)}` : ""}</> : "thinking…"}
                </span>
              </div>
              <div className="mt-3 flex items-center gap-3 border-t border-line pt-2.5">
                <button onClick={() => setOpen(!open)} className="flex items-center gap-1 text-[11px] text-faint hover:text-muted">
                  {open ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                  {steps.length} steps
                </button>
                <button onClick={() => onCancel(message.taskId!)} className="flex items-center gap-1 text-[11px] text-danger/80 hover:text-danger">
                  <Square className="h-3.5 w-3.5" /> stop
                </button>
              </div>
            </>
          ) : (
            <>
              <p className="whitespace-pre-wrap">{message.text || message.state || "done"}</p>
              {message.usage && (
                <div className="mt-3 flex items-center gap-3 border-t border-line pt-2.5">
                  <button onClick={() => setOpen(!open)} className="flex items-center gap-1 text-[11px] text-faint hover:text-muted">
                    {open ? <ChevronUp className="h-4 w-4" /> : <ChevronDown className="h-4 w-4" />}
                    {steps.length} steps · {message.usage.calls} calls
                  </button>
                  {message.frame && <span className="flex items-center gap-1 text-[10px] text-faint"><ImageIcon className="h-4 w-4" /> screen</span>}
                  {message.stopped && <span className="text-[10px] text-danger">stopped</span>}
                </div>
              )}
            </>
          )}

          {open && steps.length > 0 && (
            <div className="mt-3 max-h-72 space-y-2 overflow-y-auto border-t border-line pt-3">
              {steps.map((s, i) => (
                <div key={i} className="flex gap-3 text-[11px] leading-relaxed">
                  <span className="shrink-0 font-mono text-faint">R{s.round}</span>
                  <span className="shrink-0 font-mono uppercase tracking-wider text-teal">{s.kind}</span>
                  <span className="min-w-0 break-words text-muted">{s.action}{s.detail ? ` — ${s.detail}` : ""}</span>
                </div>
              ))}
            </div>
          )}
        </div>
        <div className="mt-1 pl-1 font-mono text-[9px] text-faint">{timeLabel(message.time)}</div>
      </div>
    </div>
  );
}
