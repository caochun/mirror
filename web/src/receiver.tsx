import { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { api, ApiError } from './api';
import './style.css';

type Content = { title: string; bodyHtml: string; versionId: string; renderToken: string; deadlineAt: string; mode: string; notice: string };

function Receiver() {
  const [grant, setGrant] = useState('');
  const [content, setContent] = useState<Content | null>(null);
  const [error, setError] = useState('');
  const [readError, setReadError] = useState('');
  const [readAt, setReadAt] = useState('');
  const [revision, setRevision] = useState(0);
  const [readAttempt, setReadAttempt] = useState(0);
  const body = useRef<HTMLDivElement>(null);
  const exchange = useRef<Promise<string> | null>(null);
  const failedImages = useRef(new Set<string>());

  useEffect(() => {
    let active = true;
    setError('');
    setContent(null);
    setReadAt('');
    setReadError('');
    failedImages.current.clear();
    async function load() {
      const fragment = new URLSearchParams(window.location.hash.substring(1));
      let id = fragment.get('session') ?? '';
      if (!id) {
        const ticket = fragment.get('ticket');
        if (!ticket) throw new Error('请从本人消息入口打开此提醒。');
        if (!exchange.current) {
          exchange.current = api<{ grantId: string }>('/receiver/exchange', { method: 'POST', body: JSON.stringify({ ticket }) })
            .then(result => result.grantId);
        }
        id = await exchange.current;
        window.history.replaceState(null, '', `${window.location.pathname}#session=${id}`);
      }
      if (!active) return;
      setGrant(id);
      const result = await api<Content>(`/receiver/${id}/content`).catch(e => {
        api(`/receiver/${id}/issues`, { method: 'POST', body: JSON.stringify({ category: 'CONTENT_FAILURE' }) }).catch(() => {});
        throw e;
      });
      if (!result.bodyHtml.trim()) throw new Error('正文暂不可用，请稍后重新打开。');
      if (active) setContent(result);
    }
    load().catch(e => {
      if (active) setError(e instanceof ApiError && (e.status === 403 || e.status === 401)
        ? '本人会话已失效，请从本人消息入口重新打开。' : e.message);
    });
    return () => { active = false; };
  }, [revision]);

  useEffect(() => {
    if (!content || !grant || !body.current || !body.current.innerHTML.trim()) return;
    let active = true;
    const frame = requestAnimationFrame(() => {
      api<{ firstReadAt: string }>(`/receiver/${grant}/read`, { method: 'POST', body: JSON.stringify({
        versionId: content.versionId, renderToken: content.renderToken, bodyRendered: true,
      }) }).then(result => {
        if (active) { setReadAt(result.firstReadAt); setReadError(''); }
      }).catch(e => { if (active) setReadError(e.message); });
    });
    return () => { active = false; cancelAnimationFrame(frame); };
  }, [content, grant, readAttempt]);

  function imageFailed(target: EventTarget) {
    if (!(target instanceof HTMLImageElement) || !content) return;
    target.hidden = true;
    if (!target.nextElementSibling?.classList.contains('receiver-image-error')) {
      const message = document.createElement('p');
      message.className = 'receiver-image-error rounded-lg bg-slate-100 p-4 text-sm text-slate-600';
      message.textContent = '图片暂无法加载，正文仍可正常阅读。';
      target.after(message);
    }
    const mediaId = target.dataset.mediaId;
    if (!mediaId || failedImages.current.has(mediaId)) return;
    failedImages.current.add(mediaId);
    api(`/receiver/${grant}/issues`, { method: 'POST', body: JSON.stringify({
      versionId: content.versionId, category: 'IMAGE_FAILURE', mediaId,
    }) }).catch(() => { /* A channel outage must not prevent reading the available text. */ });
  }

  return <main className="mx-auto min-h-screen max-w-xl bg-white px-6 py-10 sm:my-8 sm:min-h-0 sm:rounded-2xl sm:shadow-sm">
    <p className="eyebrow">廉洁提醒</p>
    <p className="mt-3 rounded-lg bg-amber-50 p-3 text-sm text-amber-900">Mock 本人阅读演示 · 模拟身份，仅用于验证提醒流程。</p>
    {error ? <div className="mt-8" role="alert"><h1 className="text-xl font-semibold">暂时无法查看提醒</h1>
      <p className="mt-4 text-sm leading-7 text-slate-600">{error}</p>
      <button className="secondary mt-6" onClick={() => setRevision(revision + 1)}>重新加载</button></div>
      : !content ? <p className="muted mt-8" role="status">正在验证本人身份并加载提醒…</p>
      : <>
        <h1 className="mt-8 text-2xl font-semibold leading-snug">{content.title}</h1>
        <p className="muted mt-3">{content.deadlineAt ? `阅读截止：${new Date(content.deadlineAt).toLocaleString('zh-CN')}` : '送达时间尚待渠道确认。'}</p>
        <div ref={body} className="reminder-body mt-8" onErrorCapture={event => imageFailed(event.target)}
          dangerouslySetInnerHTML={{ __html: content.bodyHtml }} />
        <p className="mt-8 border-t border-slate-100 pt-5 text-sm text-slate-500">{content.notice}</p>
        {readAt && <p className="mt-4 text-sm text-emerald-700" role="status">已记录本版本首次阅读：{new Date(readAt).toLocaleString('zh-CN')}</p>}
        {readError && <div className="mt-4 text-sm text-amber-800" role="alert"><p>阅读记录尚未确认：{readError}</p>
          <button className="secondary mt-3" onClick={() => setReadAttempt(readAttempt + 1)}>重试阅读记录</button></div>}
        <button className="secondary mt-6" onClick={() => setRevision(revision + 1)}>重新加载最新内容</button>
      </>}
  </main>;
}

createRoot(document.getElementById('root')!).render(<Receiver />);
