import { useEffect, useRef, useState } from "react";
import { Maximize2, Minimize2, X } from "lucide-react";
import { api } from "../lib/api";

export function AgentScreen({ onClose }: { onClose: () => void }) {
  const [src, setSrc] = useState("");
  const [full, setFull] = useState(false);
  const current = useRef("");

  useEffect(() => {
    let active = true;
    let timer = 0;
    const tick = async () => {
      try {
        const url = await api.frame();
        if (!active) {
          URL.revokeObjectURL(url);
          return;
        }
        if (current.current) URL.revokeObjectURL(current.current);
        current.current = url;
        setSrc(url);
      } catch {
        /* the agent may not have published a frame yet */
      }
      timer = window.setTimeout(() => void tick(), 1200);
    };
    void tick();
    return () => {
      active = false;
      window.clearTimeout(timer);
      if (current.current) URL.revokeObjectURL(current.current);
      current.current = "";
    };
  }, []);

  return (
    <div className={`absolute inset-0 z-20 flex items-center justify-center bg-black/60 p-4 ${full ? "fixed" : ""}`}>
      <div className={`flex w-full flex-col overflow-hidden rounded-2xl border border-line bg-ink/95 shadow-2xl ${full ? "h-full" : "max-w-md"}`}>
        <div className="flex items-center justify-between border-b border-line px-4 py-2.5">
          <div className="font-mono text-[10px] uppercase tracking-[0.22em] text-faint">Agent screen</div>
          <div className="flex items-center gap-1">
            <button title={full ? "Shrink" : "Enlarge"} onClick={() => setFull(!full)} className="btn-ghost h-8 w-8 rounded-lg">
              {full ? <Minimize2 className="h-4 w-4" /> : <Maximize2 className="h-4 w-4" />}
            </button>
            <button title="Close" onClick={onClose} className="btn-ghost h-8 w-8 rounded-lg"><X className="h-4 w-4" /></button>
          </div>
        </div>
        <div className="flex flex-1 items-center justify-center bg-black p-2">
          {src ? (
            <img src={src} alt="Agent screen" className="max-h-full max-w-full object-contain" onClick={() => setFull(!full)} />
          ) : (
            <p className="font-mono text-xs text-faint">Waiting for the agent screen…</p>
          )}
        </div>
      </div>
    </div>
  );
}
