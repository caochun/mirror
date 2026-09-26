import type { ReactNode } from 'react';
import { useEffect, useRef } from 'react';
export function ErrorBox({ message, retry }: { message: string; retry?: () => void }) {
  return <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-5 text-sm text-red-800">{message}{retry && <button className="ml-4 underline" onClick={retry}>重新加载</button>}</div>;
}
export function Heading({ title, subtitle }: { title: string; subtitle: string }) {
  return <div className="mb-7"><h1 className="text-2xl font-semibold tracking-tight">{title}</h1><p className="muted mt-2">{subtitle}</p></div>;
}
export function Field({ name, children }: { name: string; children: ReactNode }) {
  return <div><dt className="text-xs text-slate-400">{name}</dt><dd className="mt-2 text-sm">{children}</dd></div>;
}
export function Modal({ label, onClose, children }: { label: string; onClose: () => void; children: ReactNode }) {
  const dialog = useRef<HTMLDialogElement>(null);
  useEffect(() => { const element = dialog.current!; element.showModal(); return () => element.close(); }, []);
  return <dialog ref={dialog} aria-label={label} onCancel={e => { e.preventDefault(); onClose(); }} className="m-auto max-h-[90vh] w-[min(95vw,34rem)] rounded-xl bg-transparent p-0 backdrop:bg-slate-900/40">{children}</dialog>;
}
