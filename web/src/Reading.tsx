import { useEffect, useState } from 'react';
import { api } from './api';
import { ErrorBox, Heading } from './ui';

type Row = { id: string; name: string; organization: string; title: string; firstDeliveredAt: string; deadlineAt: string;
  state: string; contact: string; channelMode: string };
type Results = { items: Row[]; total: number; people: number; page: number; size: number; asOf: string };

export function Reading() {
  const [state, setState] = useState('ALL');
  const [query, setQuery] = useState('');
  const [page, setPage] = useState(0);
  const [data, setData] = useState<Results | null>(null);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    let active = true;
    setData(null);
    setError('');
    const params = new URLSearchParams({ state, query, page: String(page), size: '20' });
    api<Results>(`/reading?${params}`).then(result => { if (active) setData(result); })
      .catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [state, query, page, revision]);
  return <>
    <Heading title="阅读与逾期" subtitle="按人员当前主管单位查看已送达的未读提醒，真实阅读后自动退出当前清单。" />
    <div className="panel mb-6 flex flex-wrap items-end gap-4 p-5">
      <label className="text-sm">状态<select className="ml-3" value={state} onChange={e => { setState(e.target.value); setPage(0); }}>
        <option value="ALL">全部未读</option><option value="OVERDUE">逾期未读</option><option value="UNREAD">尚未逾期</option>
      </select></label>
      <label className="text-sm">姓名或当前单位<input className="ml-3" value={query} maxLength={100} onChange={e => { setQuery(e.target.value); setPage(0); }} /></label>
      <button className="secondary" onClick={() => setRevision(revision + 1)}>刷新清单</button>
    </div>
    {error && <ErrorBox message={error} retry={() => setRevision(revision + 1)} />}
    {!data && !error && <p role="status">正在加载当前范围内的未读提醒…</p>}
    {data && <section className="panel p-5">
      <p className="mb-4 text-sm">{data.total} 条未读提醒 · 涉及 {data.people} 人 · 截至 {new Date(data.asOf).toLocaleString('zh-CN')}</p>
      {data.total === 0 ? <p className="muted py-10 text-center">当前条件下没有未读提醒。</p> : <div className="overflow-x-auto">
        <table className="w-full text-left text-sm"><thead><tr className="border-b text-slate-500">
          {['姓名', '当前单位', '提醒', '状态', '送达时间', '阅读截止', '联系方式', '数据模式'].map(h => <th className="p-3" key={h}>{h}</th>)}
        </tr></thead><tbody>{data.items.map(row => <tr className="border-b border-slate-100" key={row.id}>
          <td className="p-3">{row.name}</td><td className="p-3">{row.organization}</td><td className="p-3">{row.title}</td>
          <td className={`p-3 ${row.state === 'OVERDUE' ? 'text-amber-700' : ''}`}>{row.state === 'OVERDUE' ? '逾期未读' : '未读'}</td>
          <td className="whitespace-nowrap p-3">{new Date(row.firstDeliveredAt).toLocaleString('zh-CN')}</td>
          <td className="whitespace-nowrap p-3">{new Date(row.deadlineAt).toLocaleString('zh-CN')}</td>
          <td className="p-3">{row.contact}</td><td className="p-3">{row.channelMode === 'mock' ? 'Mock 演示' : '业务数据'}</td>
        </tr>)}</tbody></table>
      </div>}
      <div className="mt-5 flex items-center gap-4 text-sm">
        <button className="secondary" disabled={page === 0} onClick={() => setPage(page - 1)}>上一页</button>
        <span>第 {page + 1} 页</span>
        <button className="secondary" disabled={(page + 1) * data.size >= data.total} onClick={() => setPage(page + 1)}>下一页</button>
      </div>
    </section>}
  </>;
}
