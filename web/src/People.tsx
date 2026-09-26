import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, useParams } from 'react-router-dom';
import { api } from './api';
import type { Organization, Page, Person, Detail } from './api';
import { useSession } from './App';
import { ErrorBox, Heading, Field } from './ui';
import { PersonTags } from './Tags';

export function People() {
  const { actor } = useSession(); const [organizations, setOrganizations] = useState<Organization[]>([]);
  const [organization, setOrganization] = useState(''); const [query, setQuery] = useState(''); const [search, setSearch] = useState(''); const [status, setStatus] = useState('ACTIVE');
  const [page, setPage] = useState(0); const [data, setData] = useState<Page<Person> | null>(null); const [error, setError] = useState(''); const [loading, setLoading] = useState(true); const [refresh, setRefresh] = useState(0);
  useEffect(() => { if (actor.role !== 'REVIEWER') api<Organization[]>('/organizations').then(setOrganizations).catch(e => setError(e.message)); }, [actor]);
  useEffect(() => {
    if (actor.role === 'REVIEWER') return;
    const controller = new AbortController(); setLoading(true); setError('');
    api<Page<Person>>(`/people?${new URLSearchParams({ q: search, organization, status, page: String(page), size: '10' })}`, { signal: controller.signal })
      .then(setData).catch(e => { if (e.name !== 'AbortError') setError(e.message); })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [actor, search, organization, status, page, refresh]);
  if (actor.role === 'REVIEWER') return <ErrorBox message="审核员无人员库浏览权限" />;
  function orgTree(parentId: string | null, depth = 0): ReactNode {
    return organizations.filter(o => o.parentId === parentId).map(o => <div key={o.id}>
      <button onClick={() => { setOrganization(o.id); setPage(0); }} style={{ paddingLeft: 12 + depth * 16 }} className={`my-0.5 w-full rounded-lg py-2.5 pr-3 text-left text-sm ${organization === o.id ? 'bg-blue-50 font-medium text-blue-700' : 'text-slate-600 hover:bg-slate-50'}`}>{o.name}</button>
      {depth < 20 && orgTree(o.id, depth + 1)}
    </div>);
  }
  return <><Heading title="人员与组织" subtitle="核对当前人员归属，查看基础档案与变更轨迹。" />
    <div className="grid items-start gap-5 xl:grid-cols-[230px_minmax(0,1fr)]">
      <aside className="panel p-4"><h2 className="mb-4 text-sm font-semibold">组织范围</h2>
        <button className={`w-full rounded-lg px-3 py-2.5 text-left text-sm ${!organization ? 'bg-blue-50 font-medium text-blue-700' : ''}`} onClick={() => { setOrganization(''); setPage(0); }}>全部授权范围</button>
        {orgTree(null)}<p className="mt-5 border-t border-slate-100 pt-4 text-xs leading-5 text-slate-400">选择组织查看本级人员。全部授权范围包含已授权的下级单位。</p>
      </aside>
      <section className="panel min-w-0">
        <form onSubmit={e => { e.preventDefault(); setSearch(query); setPage(0); }} className="flex flex-wrap items-center gap-3 border-b border-slate-100 p-5">
          <input aria-label="姓名或工号" placeholder="搜索姓名或工号" value={query} onChange={e => setQuery(e.target.value)} />
          <select aria-label="人员状态" value={status} onChange={e => { setStatus(e.target.value); setPage(0); }}><option value="ACTIVE">有效人员</option><option value="INACTIVE">已停用</option><option value="">全部状态</option></select>
          <button className="primary">查询</button><button type="button" className="secondary" onClick={() => { setQuery(''); setSearch(''); setStatus('ACTIVE'); setPage(0); setOrganization(''); }}>重置</button>
        </form>
        <div className="flex items-center justify-between px-5 py-4"><h2 className="text-sm font-medium">人员目录 <span className="ml-2 text-slate-400">{loading ? '…' : data?.total ?? '—'} 人</span></h2><span className="text-xs text-slate-400">基础资料按授权范围展示</span></div>
        {error ? <div className="p-5"><ErrorBox message={error} retry={() => setRefresh(v => v + 1)} /></div> : loading ? <div role="status" className="p-10 text-center text-sm text-slate-400">正在加载人员…</div> : <>
          <div className="overflow-x-auto"><table className="w-full"><thead className="bg-slate-50"><tr>{['姓名 / 工号', '当前单位', '职务摘要', '身份关联', '状态'].map(h => <th key={h} className="px-5 py-3">{h}</th>)}</tr></thead>
            <tbody>{data?.items.map(p => <tr key={p.id} className="border-t border-slate-100 hover:bg-slate-50/70">
              <td className="px-5 py-4"><Link className="font-medium text-blue-700 hover:underline" to={`/people/${encodeURIComponent(p.id)}`}>{p.name}</Link><p className="mt-1 text-xs text-slate-400">{p.employeeNo || '未提供工号'}</p></td>
              <td className="px-5 py-4">{p.organizationName}</td><td className="px-5 py-4 text-slate-500">{p.title || '未提供'}</td>
              <td className="px-5 py-4"><span className={`rounded-md px-2 py-1 text-xs ${p.identityStatus === 'OK' ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-800'}`}>{p.identityStatus === 'OK' ? '正常' : '待核实'}</span></td>
              <td className="px-5 py-4 text-slate-500">{p.status === 'ACTIVE' ? '有效' : '已停用'}</td>
            </tr>)}</tbody></table>{data?.total === 0 && <p className="muted p-10 text-center">当前条件下没有人员，请调整筛选条件。</p>}
          </div>
          <div className="flex items-center justify-between border-t border-slate-100 px-5 py-4"><span className="text-xs text-slate-400">第 {page + 1} 页 · 每页 10 人</span><div className="flex gap-2"><button className="secondary" disabled={page === 0} onClick={() => setPage(p => p - 1)}>上一页</button><button className="secondary" disabled={!data || (page + 1) * 10 >= data.total} onClick={() => setPage(p => p + 1)}>下一页</button></div></div>
        </>}
      </section>
    </div>
  </>;
}

export function PersonDetail() {
  const { id } = useParams(); const [data, setData] = useState<Detail | null>(null); const [error, setError] = useState('');
  useEffect(() => {
    setData(null); setError(''); const controller = new AbortController();
    api<Detail>(`/people/${encodeURIComponent(id!)}`, { signal: controller.signal }).then(setData).catch(e => { if (e.name !== 'AbortError') setError(e.message); });
    return () => controller.abort();
  }, [id]);
  return <><Link className="mb-6 inline-block text-sm text-slate-500 hover:text-blue-700" to="/people">← 返回人员目录</Link>
    {error ? <ErrorBox message={error} /> : !data ? <p role="status">正在加载档案…</p> : <>
      <Heading title={data.person.name} subtitle={`${data.person.organizationName} · ${data.person.title || '职务未提供'}`} />
      <div className="grid gap-6 lg:grid-cols-2"><section className="panel p-6"><h2 className="mb-6 font-semibold">基础档案</h2>
        <dl className="grid grid-cols-2 gap-6"><Field name="工号">{data.person.employeeNo || '未提供'}</Field><Field name="当前单位">{data.person.organizationName}</Field><Field name="人员状态">{data.person.status === 'ACTIVE' ? '有效' : '已停用'}</Field><Field name="身份关联">{data.person.identityStatus === 'OK' ? '正常' : '待核实'}</Field></dl>
        <p className="muted mt-8 border-t border-slate-100 pt-5">基础信息需由权威来源核实。本页面仅展示当前已接入字段。</p>
      </section><section className="panel p-6"><h2 className="mb-6 font-semibold">档案变更记录</h2>
        <ol className="space-y-6">{data.history.map(h => <li key={h.version} className="border-l-2 border-blue-100 pl-5"><p className="text-sm font-medium">{h.operation === 'CREATED' ? '建立档案' : '档案变更'} <span className="text-xs text-slate-400">版本 {h.version}</span></p><p className="mt-2 text-sm text-slate-500">{h.name} · {h.status === 'ACTIVE' ? '有效' : h.status}</p><p className="mt-2 text-xs text-slate-400">记录于 {new Date(h.recordedAt).toLocaleString('zh-CN')}</p></li>)}</ol>
      </section></div>
      <PersonTags personId={data.person.id} active={data.person.status === 'ACTIVE'} />
    </>}
  </>;
}
