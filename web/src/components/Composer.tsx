import { useEffect, useRef, useState } from "react";
import { ArrowUp, Paperclip } from "lucide-react";

export function Composer({ onSend, replying, disabled }: {
  onSend: (text: string) => void;
  replying?: string;
  disabled?: boolean;
}) {
  const [value, setValue] = useState("");
  const ref = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    el.style.height = "0px";
    el.style.height = `${Math.min(el.scrollHeight, 180)}px`;
  }, [value]);

  useEffect(() => {
    if (replying) ref.current?.focus();
  }, [replying]);

  const send = () => {
    const text = value.trim();
    if (!text || disabled) return;
    onSend(text);
    setValue("");
  };

  return (
    <div className="pointer-events-none absolute inset-x-0 bottom-0 z-10 bg-gradient-to-t from-ink via-ink to-transparent px-4 pb-5 pt-10 sm:px-0">
      <div className="pointer-events-auto mx-auto max-w-3xl">
        {replying && (
          <div className="mb-2 flex items-center gap-2 pl-2 text-[11px] text-persimmon">
            <span className="h-1.5 w-1.5 animate-pulse rounded-full bg-persimmon" />
            Replying to agent: {replying.slice(0, 60)}
          </div>
        )}
        <div className="flex items-end gap-2 rounded-2xl border border-line bg-surface/95 p-2 shadow-lg shadow-black/10 backdrop-blur-xl transition-colors focus-within:border-persimmon/50">
          <button className="btn-ghost mb-0.5 h-10 w-10 shrink-0 rounded-xl text-[#4a453e]" title="Attachments are not supported yet" disabled>
            <Paperclip className="h-5 w-5" />
          </button>
          <textarea
            ref={ref}
            rows={1}
            value={value}
            onChange={(e) => setValue(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); send(); }
            }}
            placeholder={replying ? "Type your reply…" : "Tell Orbit what to do on your phone…"}
            className="max-h-44 min-h-[38px] flex-1 resize-none bg-transparent py-2 text-[15px] leading-relaxed text-linen outline-none placeholder:text-[#6b665e]"
          />
          <button
            onClick={send}
            disabled={!value.trim() || disabled}
            className="mb-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-persimmon text-ink transition-all hover:brightness-110 disabled:cursor-not-allowed disabled:opacity-30"
          >
            <ArrowUp className="h-5 w-5" />
          </button>
        </div>
        <p className="mt-2 text-center font-mono text-[9px] uppercase tracking-widest text-[#6b665e]">
          Enter to send · Shift+Enter for a new line
        </p>
      </div>
    </div>
  );
}
