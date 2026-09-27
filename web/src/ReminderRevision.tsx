import { useEffect, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api } from './api';
import { ErrorBox, Heading } from './ui';
import { RichTextEditor } from './RichTextEditor';
import type { ReminderDetail } from './reminderTypes';
import { readingWindows } from './reminderTypes';

export function ReminderRevision() {
  const { id } = useParams();
  const navigate = useNavigate();
  const [detail, setDetail] = useState<ReminderDetail | null>(null);
  const [title, setTitle] = useState('');
  const [body, setBody] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [command, setCommand] = useState<{ request: string; key: string } | null>(null);
  useEffect(() => {
    Promise.all([api<ReminderDetail>(`/reminders/${id}`), api<string[]>('/auth/permissions')]).then(([d, permissions]) => {
      if (!permissions.includes('REMINDER_REVISE')) throw new Error('无权修订提醒内容。');
      setDetail(d); setTitle(d.previewTitle); setBody(d.bodyHtml);
    }).catch(e => setError(e.message));
  }, [id]);
  async function save() {
    if (!detail) return;
    setBusy(true); setError('');
    const request = JSON.stringify({ expectedVersion: detail.task.version, title, bodyHtml: body });
    const key = command?.request === request ? command.key : crypto.randomUUID();
    setCommand({ request, key });
    try {
      await api(`/reminders/${id}/revision`, { method: 'POST', headers: { 'Idempotency-Key': key }, body: request });
      navigate(`/reminders/${id}`);
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }
  return <>
    <Link className="mb-5 inline-block text-sm text-blue-700" to={`/reminders/${id}`}>← 返回提醒详情</Link>
    <Heading title="修订提醒内容" subtitle="仅修改标题、正文、图片和链接。原名单及阅读截止不变，重新审核发布后才对接收人可见。" />
    {error && <ErrorBox message={error} />}
    {!detail && !error && <p role="status">正在加载已发布内容…</p>}
    {detail && <section className="panel space-y-5 p-6">
      <p className="rounded-lg bg-blue-50 p-4 text-sm text-blue-900">原名单 {detail.task.recipientCount} 人，阅读时限 {readingWindows[detail.task.readingWindow]}。
        发布新版本会要求重新阅读，但不会延长任何人的原截止时间，也不重发已成功的通知。</p>
      <label className="block text-sm">修订标题<input className="mt-2 w-full" value={title} maxLength={120} onChange={event => setTitle(event.target.value)} /></label>
      <RichTextEditor value={body} onChange={setBody} onUploadingChange={setUploading} />
      <button className="primary" disabled={busy || uploading || !title.trim()} onClick={save}>保存修订并核对</button>
    </section>}
  </>;
}
