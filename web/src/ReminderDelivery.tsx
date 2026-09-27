import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import { ErrorBox } from './ui';
import type { ReminderTask } from './reminderTypes';

type Recipient = {
  id: string;
  name: string;
  organization: string;
  deliveryState: string;
  readState: string;
  firstDeliveredAt: string;
  deadlineAt: string;
  channelMode: string;
};
type Delivery = { mode: string; mockEnabled: boolean; recipients: Recipient[] };
const states: Record<string, string> = {
  PENDING: '待发送', SUBMITTED: '已提交', DELIVERED: '已送达', FAILED: '发送失败', UNKNOWN: '结果未知',
};
const time = (value: string) => value ? new Date(value).toLocaleString('zh-CN') : '—';

export function ReminderDelivery({ task, canWrite, canRetry, refresh }: {
  task: ReminderTask;
  canWrite: boolean;
  canRetry: boolean;
  refresh: () => void;
}) {
  const [data, setData] = useState<Delivery | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(0);
  const pending = useRef<{ request: string; key: string } | null>(null);
  useEffect(() => {
    let active = true;
    api<Delivery>(`/reminders/${task.id}/delivery`).then(result => {
      if (active) { setData(result); setError(''); }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [task.id, task.version]);

  async function run(path: 'mock-dispatch' | 'retry') {
    const prompt = path === 'retry' ? '仅重试原名单中发送失败或结果未知的人员，确认继续？'
      : '使用演示渠道模拟发送，不向外部人员发送消息。确认继续？';
    if (!window.confirm(prompt)) return;
    setBusy(true);
    setError('');
    const request = `${task.id}/${task.version}/${path}`;
    if (pending.current?.request !== request) pending.current = { request, key: crypto.randomUUID() };
    try {
      await api(`/reminders/${task.id}/${path}`, {
        method: 'POST', headers: { 'Idempotency-Key': pending.current.key },
        body: JSON.stringify({ expectedVersion: task.version }),
      });
      pending.current = null;
      setData(await api<Delivery>(`/reminders/${task.id}/delivery`));
      refresh();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  return <section className="panel mb-6 p-6" aria-label="送达与阅读结果">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="font-semibold">送达与阅读</h2>
      <button className="secondary" onClick={refresh} disabled={busy}>刷新结果</button>
    </div>
    {error && <div className="mt-3"><ErrorBox message={error} retry={refresh} /></div>}
    {!data && !error && <p role="status" className="muted mt-3">正在加载结果…</p>}
    {data && <>
      <p className="muted mt-3">
        {data.mode === 'mock' ? 'Mock 演示渠道 · 当前送达结果为模拟数据，未向外部人员发送消息。'
          : data.mode === 'disabled' ? '发送渠道未启用，审核通过的任务保留在队列中。' : '送达结果由渠道确认。'}
      </p>
      <div className="mt-4 flex flex-wrap gap-3">
        {canWrite && data.mockEnabled && ['APPROVED_WAITING', 'SENDING'].includes(task.state) &&
          <button className="secondary" disabled={busy} onClick={() => run('mock-dispatch')}>执行 Mock 发送</button>}
        {canRetry && ['PARTIAL_FAILED', 'ALL_FAILED'].includes(task.state) &&
          <button className="secondary" disabled={busy} onClick={() => run('retry')}>重试失败或未知人员</button>}
      </div>
      {data.recipients.length > 0 ? <>
        <p className="mt-4 text-sm">
          目标 {data.recipients.length} 人 · 已送达 {data.recipients.filter(r => r.deliveryState === 'DELIVERED').length} 人
          {' · '}最新版本已读 {data.recipients.filter(r => r.readState === 'READ').length} 人
        </p>
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead><tr className="border-b text-slate-500">
              <th className="p-2">姓名</th><th className="p-2">发送时单位</th><th className="p-2">送达</th>
              <th className="p-2">最新版本阅读</th><th className="p-2">首次送达</th><th className="p-2">阅读截止</th>
            </tr></thead>
            <tbody>{data.recipients.slice(page * 20, (page + 1) * 20).map(recipient => <tr key={recipient.id} className="border-b border-slate-100">
              <td className="p-2">{recipient.name}</td><td className="p-2">{recipient.organization}</td>
              <td className="p-2">{states[recipient.deliveryState] ?? recipient.deliveryState}</td>
              <td className="p-2">{recipient.readState === 'READ' ? '已读' : '未读'}</td>
              <td className="whitespace-nowrap p-2">{time(recipient.firstDeliveredAt)}</td>
              <td className="whitespace-nowrap p-2">{time(recipient.deadlineAt)}</td>
            </tr>)}</tbody>
          </table>
        </div>
        {data.recipients.length > 20 && <div className="mt-4 flex items-center gap-3 text-sm">
          <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button>
          <span>第 {page + 1} 页 / 共 {Math.ceil(data.recipients.length / 20)} 页</span>
          <button className="secondary" disabled={(page + 1) * 20 >= data.recipients.length} onClick={() => setPage(page + 1)}>下一页</button>
        </div>}
      </> : <p className="muted mt-4">内容尚未发布，暂无逐人送达或阅读结果。</p>}
    </>}
  </section>;
}
