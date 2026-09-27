import { useEffect, useRef, useState } from 'react';
import { api } from './api';
import type { Page } from './api';
import { ErrorBox, Modal } from './ui';
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
  withdrawalState: string;
  withdrawalId: string;
  withdrawalError: string;
};
type Delivery = { mode: string; mockEnabled: boolean; recipients: Recipient[] };
const states: Record<string, string> = {
  PENDING: '待发送', SUBMITTED: '已提交', DELIVERED: '已送达', FAILED: '发送失败', UNKNOWN: '结果未知',
};
const time = (value: string) => value ? new Date(value).toLocaleString('zh-CN') : '—';

export function ReminderDelivery({ task, canWrite, canRetry, canWithdraw, refresh }: {
  task: ReminderTask;
  canWrite: boolean;
  canRetry: boolean;
  canWithdraw: boolean;
  refresh: () => void;
}) {
  const [data, setData] = useState<Delivery | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [page, setPage] = useState(0);
  const [entry, setEntry] = useState('');
  const [selected, setSelected] = useState<string[]>([]);
  const [withdrawMode, setWithdrawMode] = useState<'' | 'request' | 'retry'>('');
  const [historyOpen, setHistoryOpen] = useState(false);
  const [reason, setReason] = useState('');
  const withdrawalKey = useRef<{ request: string; key: string } | null>(null);
  const pending = useRef<{ request: string; key: string } | null>(null);
  useEffect(() => {
    let active = true;
    api<Delivery>(`/reminders/${task.id}/delivery`).then(result => {
      if (active) { setData(result); setError(''); }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [task.id, task.version]);

  async function refreshResults() {
    setBusy(true);
    setError('');
    try {
      setData(await api<Delivery>(`/reminders/${task.id}/delivery`));
      refresh();
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setBusy(false);
    }
  }

  useEffect(() => {
    if (task.state !== 'WITHDRAWING' && !data?.recipients.some(recipient => recipient.withdrawalState === 'REQUESTED')) return;
    const timer = window.setInterval(refreshResults, 1500);
    return () => window.clearInterval(timer);
  }, [data?.recipients, task.id, task.state]);

  async function withdraw() {
    const payload = { expectedVersion: task.version, recipientIds: selected, reason };
    const request = JSON.stringify({ mode: withdrawMode, ...payload });
    if (withdrawalKey.current?.request !== request) withdrawalKey.current = { request, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      await api(`/reminders/${task.id}/withdrawals${withdrawMode === 'retry' ? '/retry' : ''}`, {
        method: 'POST', headers: { 'Idempotency-Key': withdrawalKey.current.key }, body: JSON.stringify(payload),
      });
      withdrawalKey.current = null;
      setWithdrawMode(''); setReason(''); setSelected([]);
      setData(await api<Delivery>(`/reminders/${task.id}/delivery`));
      refresh();
    } catch (e) { setError((e as Error).message); }
    finally { setBusy(false); }
  }

  const selectedRows = data?.recipients.filter(recipient => selected.includes(recipient.id)) ?? [];
  const selectable = (recipient: Recipient) => ['NONE', 'FAILED', 'UNKNOWN'].includes(recipient.withdrawalState);
  const withdrawalNames: Record<string, string> = { NONE: '未申请', REQUESTED: '撤回处理中', WITHDRAWN: '已撤回', FAILED: '撤回失败', UNKNOWN: '撤回结果未知' };

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


  async function openReceiver(recipient: Recipient) {
    const popup = window.open('about:blank', '_blank');
    if (popup) popup.opener = null;
    setError('');
    try {
      const result = await api<{ url: string }>(`/reminders/${task.id}/recipients/${recipient.id}/mock-entry`, { method: 'POST' });
      if (popup) popup.location.replace(result.url);
      else setEntry(result.url);
    } catch (e) {
      popup?.close();
      setError((e as Error).message);
    }
  }

  return <section className="panel mb-6 p-6" aria-label="送达与阅读结果">
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h2 className="font-semibold">送达与阅读</h2>
      <button className="secondary" onClick={refreshResults} disabled={busy}>刷新结果</button>
    </div>
    {error && <div className="mt-3"><ErrorBox message={error} retry={refreshResults} /></div>}
    {!data && !error && <p role="status" className="muted mt-3">正在加载结果…</p>}
    {entry && <a href={entry} target="_blank" rel="noopener noreferrer" className="mt-3 block text-blue-700">打开本人阅读演示</a>}
    {data && <>
      <p className="muted mt-3">
        {data.mode === 'mock' ? 'Mock 演示渠道 · 当前送达结果为模拟数据，未向外部人员发送消息。'
          : data.mode === 'disabled' ? '发送渠道未启用，审核通过的任务保留在队列中。' : '送达结果由渠道确认。'}
      </p>
      {data.mode !== 'mock' && data.recipients.some(recipient => recipient.channelMode === 'mock') &&
        <p className="mt-2 text-sm text-amber-800">此任务包含 Mock 演示结果，不代表真实渠道送达。</p>}
      <div className="mt-4 flex flex-wrap gap-3">
        {canWrite && data.mockEnabled && ['APPROVED_WAITING', 'SENDING', 'WITHDRAWING', 'PARTIAL_WITHDRAWN', 'WITHDRAW_FAILED'].includes(task.state) &&
          <button className="secondary" disabled={busy} onClick={() => run('mock-dispatch')}>执行 Mock 发送</button>}
        {canRetry && ['PARTIAL_FAILED', 'ALL_FAILED', 'PARTIAL_WITHDRAWN', 'WITHDRAW_FAILED'].includes(task.state) &&
          <button className="secondary" disabled={busy} onClick={() => run('retry')}>重试失败或未知人员</button>}
      </div>
      {data.recipients.length > 0 ? <>
        <p className="mt-4 text-sm">
          目标 {data.recipients.length} 人 · 已送达 {data.recipients.filter(r => r.deliveryState === 'DELIVERED').length} 人
          {' · '}最新版本已读 {data.recipients.filter(r => r.readState === 'READ').length} 人
          {' · '}已撤回 {data.recipients.filter(r => r.withdrawalState === 'WITHDRAWN').length} 人
        </p>
        {canWithdraw && <div className="mt-4 flex flex-wrap items-center gap-3 text-sm">
          <button className="secondary" disabled={busy} onClick={() => setSelected(data.recipients.filter(r => r.withdrawalState === 'NONE').map(r => r.id))}>选择全部未申请人员</button>
          <button className="secondary" disabled={busy} onClick={() => setSelected(data.recipients.filter(r => ['FAILED', 'UNKNOWN'].includes(r.withdrawalState)).map(r => r.id))}>选择撤回失败或未知人员</button>
          <button className="secondary" disabled={busy || selectedRows.length === 0 || selectedRows.some(r => r.withdrawalState !== 'NONE')}
            onClick={() => setWithdrawMode('request')}>撤回所选 {selectedRows.length} 人</button>
          <button className="secondary" disabled={busy || selectedRows.length === 0 || selectedRows.some(r => !['FAILED', 'UNKNOWN'].includes(r.withdrawalState))}
            onClick={() => setWithdrawMode('retry')}>重试所选撤回</button>
        </div>}
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead><tr className="border-b text-slate-500">
              {canWithdraw && <th className="p-2">选择</th>}
              <th className="p-2">姓名</th><th className="p-2">发送时单位</th><th className="p-2">送达</th>
              <th className="p-2">撤回</th><th className="p-2">最新版本阅读</th><th className="p-2">首次送达</th><th className="p-2">阅读截止</th>{canWrite && data.mockEnabled && <th className="p-2">演示</th>}
            </tr></thead>
            <tbody>{data.recipients.slice(page * 20, (page + 1) * 20).map(recipient => <tr key={recipient.id} className="border-b border-slate-100">
              {canWithdraw && <td className="p-2"><input type="checkbox" aria-label={`选择撤回 ${recipient.name}`}
                disabled={busy || !selectable(recipient)} checked={selected.includes(recipient.id)}
                onChange={event => setSelected(previous => event.target.checked ? [...previous, recipient.id] : previous.filter(id => id !== recipient.id))} /></td>}
              <td className="p-2">{recipient.name}</td><td className="p-2">{recipient.organization}</td>
              <td className="p-2">{states[recipient.deliveryState] ?? recipient.deliveryState}</td>
              <td className="p-2">{withdrawalNames[recipient.withdrawalState] ?? '待核实'}
                {['FAILED', 'UNKNOWN'].includes(recipient.withdrawalState) && recipient.withdrawalError &&
                  <p className="muted mt-1">{recipient.withdrawalError === 'CHANNEL_UNCONFIRMED' ? '渠道结果尚未确认' : recipient.withdrawalError === 'WITHDRAWAL_UNSUPPORTED' ? '当前渠道不支持撤回' : recipient.withdrawalError}</p>}
              </td>
              <td className="p-2">{recipient.readState === 'READ' ? '已读' : '未读'}</td>
              <td className="whitespace-nowrap p-2">{time(recipient.firstDeliveredAt)}</td>
              <td className="whitespace-nowrap p-2">{time(recipient.deadlineAt)}</td>
              {canWrite && data.mockEnabled && <td className="p-2"><button className="secondary whitespace-nowrap" disabled={recipient.withdrawalState === 'WITHDRAWN'}
                aria-label={`${recipient.name} 的本人阅读演示`} onClick={() => openReceiver(recipient)}>模拟本人阅读</button></td>}
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
    {data?.recipients.some(recipient => recipient.withdrawalId) && <div className="mt-5 border-t border-slate-100 pt-5">
      <button className="secondary" onClick={() => setHistoryOpen(!historyOpen)}>{historyOpen ? '收起撤回记录' : '查看撤回记录'}</button>
      {historyOpen && <WithdrawalHistory task={task} />}
    </div>}
    {withdrawMode && <Modal label="确认撤回提醒" onClose={() => { if (!busy) setWithdrawMode(''); }}>
      <div className="panel p-6">
        <h2 className="text-lg font-semibold">{withdrawMode === 'retry' ? '确认重试撤回' : '确认撤回提醒'}</h2>
        <p className="muted mt-3">本次处理 {selectedRows.length} 人。撤回成功后无法再查看正文，原送达和阅读历史保留。
          如有未发布修订，将终止其审核或发布。</p>
        <label className="mt-4 block text-sm">撤回原因<textarea className="mt-2 w-full" maxLength={1000} value={reason} onChange={event => setReason(event.target.value)} /></label>
        {error && <ErrorBox message={error} />}
        <div className="mt-5 flex justify-end gap-3"><button className="secondary" disabled={busy} onClick={() => setWithdrawMode('')}>返回</button>
          <button className="primary" disabled={busy || !reason.trim()} onClick={withdraw}>确认提交撤回</button></div>
      </div>
    </Modal>}
  </section>;
}


type WithdrawalHistoryRow = { id: string; name: string; state: string; reason: string; requestedBy: string; requestedAt: string; channelMode: string;
  results: { eventId: string; outcome: string; occurredAt: string; errorCode: string }[] };
function WithdrawalHistory({ task }: { task: ReminderTask }) {
  const [data, setData] = useState<Page<WithdrawalHistoryRow> | null>(null);
  const [page, setPage] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    let active = true;
    setError('');
    api<Page<WithdrawalHistoryRow>>(`/reminders/${task.id}/withdrawals?page=${page}&size=20`)
      .then(result => { if (active) setData(result); }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [task.id, task.version, page]);
  const states: Record<string, string> = { REQUESTED: '已申请', RUNNING: '处理中', WITHDRAWN: '撤回成功', FAILED: '撤回失败', UNKNOWN: '结果未知' };
  return <div className="mt-5"><h3 className="font-semibold">撤回记录</h3>
    {error && <ErrorBox message={error} />}
    {data && <>
      <ul className="mt-3 space-y-4">{data.items.map(record => <li key={record.id} className="rounded-lg border border-slate-100 p-4 text-sm">
        <p>{record.name} · {states[record.state]} · {record.requestedBy} · {time(record.requestedAt)} · {record.channelMode === 'mock' ? 'Mock渠道' : record.channelMode === 'local' ? '未提交前本地取消' : '渠道处理'}</p>
        <p className="mt-2">{record.reason}</p>
        <details className="mt-3 text-slate-500"><summary>处理回执 {record.results.length} 条</summary>
          <ul className="mt-2 space-y-1">{record.results.map(result => <li key={result.eventId}>{time(result.occurredAt)} · {states[result.outcome]}
            {result.errorCode && ` · ${result.errorCode}`}</li>)}</ul>
        </details>
      </li>)}</ul>
      <div className="mt-4 flex items-center gap-3 text-sm"><button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页记录</button>
        <span>共 {data.total} 次申请</span><button className="secondary" disabled={(page + 1) * data.size >= data.total} onClick={() => setPage(page + 1)}>下一页记录</button></div>
    </>}
  </div>;
}
