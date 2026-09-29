import { useEffect, useState } from 'react';
import { objectNames, relationNames, operationNames, objectLabel, timeLabel, relationCaption } from './object-labels';

type Key = { type: string; id: string };
type Presentation = { title: string | null; summary: string; facts: { label: string; value: string; target: Key | null }[] };
type Obj = Key & { version: number; properties: Record<string, unknown>; presentation?: Presentation };
type PresentedObject = { object: Obj; presentation: Presentation };
const present = (value: PresentedObject): Obj => ({ ...value.object, presentation: value.presentation });
type History = { version: number; operation: string; actorId: string | null; recordedAt: string; validFrom: string; validTo: string | null; state: unknown };
type Relation = { id: string; type: string; from: Key; to: Key; direction: string; target: Obj | null; targetPresentation: Presentation | null; ended: boolean; version: number; validFrom: string; validTo: string | null };
type Page = { items: Relation[]; total: number; offset: number; limit: number };
type Schema = { objectTypes: { name: string }[]; linkTypes: { name: string; fromType: string; toType: string }[] };
async function read<T>(url: string): Promise<T> {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`读取失败（${response.status}）`);
  return JSON.parse(await response.text() || 'null');
}
const objectPath = (key: Key) => `${encodeURIComponent(key.type)}/${encodeURIComponent(key.id)}`;
const same = (a: Key, b: Key) => a.type === b.type && a.id === b.id;

export default function ObjectsPanel({ close, initial }: { close: () => void; initial?: Key }) {
  const [schema, setSchema] = useState<Schema>({ objectTypes: [], linkTypes: [] });
  const [listType, setListType] = useState(initial?.type || 'Person');
  const [offset, setOffset] = useState(0);
  const [rows, setRows] = useState<Obj[]>([]);
  const [focus, setFocus] = useState<Key | undefined>(initial);
  const [object, setObject] = useState<Obj>();
  const [history, setHistory] = useState<History[]>([]);
  const [page, setPage] = useState<Page>({ items: [], total: 0, offset: 0, limit: 20 });
  const [relationOffset, setRelationOffset] = useState(0);
  const [relationshipType, setRelationshipType] = useState('');
  const [includeEnded, setIncludeEnded] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [relationError, setRelationError] = useState('');
  const [trail, setTrail] = useState<(Key & { label: string })[]>([]);
  useEffect(() => { read<{ schema: Schema }>('/api/model').then(value => setSchema(value.schema)).catch(e => setError(e.message)); }, []);
  useEffect(() => {
    let active = true;
    setRows([]); setError('');
    read<PresentedObject[]>(`/api/objects/${listType}/presentations?offset=${offset}&limit=20`).then(result => {
      const value = result.map(present);
      if (active) { setRows(value); setFocus(previous => previous || value[0]); }
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [listType, offset]);
  useEffect(() => {
    let active = true;
    setObject(undefined); setHistory([]); setError('');
    if (focus) Promise.all([read<PresentedObject>(`/api/objects/${objectPath(focus)}/presentation`), read<History[]>(`/api/v1/${objectPath(focus)}/history`)])
      .then(([view, history]) => {
        const obj = present(view);
        if (!active) return;
        setObject(obj); setHistory(history);
        setTrail(previous => previous.length ? previous.map(item => same(item, obj) ? { ...item, label: objectLabel(obj) } : item)
          : [{ type: obj.type, id: obj.id, label: objectLabel(obj) }]);
      }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [focus?.type, focus?.id]);
  useEffect(() => {
    let active = true;
    setPage({ items: [], total: 0, offset: relationOffset, limit: 20 }); setRelationError('');
    if (!focus) return;
    setLoading(true);
    const query = new URLSearchParams({ includeEnded: String(includeEnded), relationshipType, offset: String(relationOffset), limit: '20' });
    read<Page>(`/api/objects/${objectPath(focus)}/relationships?${query}`)
      .then(page => { if (active) setPage({ ...page, items: page.items.map(link => ({ ...link, target: link.target ? { ...link.target, presentation: link.targetPresentation || undefined } : null })) }); }).catch(e => { if (active) setRelationError(e.message); })
      .finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [focus?.type, focus?.id, relationOffset, relationshipType, includeEnded]);
  function navigate(target: Obj, fromList = false) {
    if (focus && same(focus, target)) return;
    setTrail(previous => fromList ? [{ ...target, label: objectLabel(target) }] : [...previous, { ...target, label: objectLabel(target) }]);
    setFocus({ type: target.type, id: target.id }); setListType(target.type); setOffset(0);
    setRelationshipType(''); setRelationOffset(0);
  }
  return <main className="max-w-7xl mx-auto p-4 md:p-8 space-y-4">
    <div className="flex flex-wrap justify-between items-center gap-3"><div><h1 className="text-xl font-semibold">对象浏览</h1><p className="text-sm text-slate-500 mt-1">了解人员、任职和标签等业务信息，点击名称继续查看</p></div><button className="btn" onClick={close}>返回人员工作台</button></div>
    {error && <p role="alert" className="text-red-700">{error}</p>}
    <div className="grid md:grid-cols-[260px_minmax(0,1fr)] gap-4 items-start"><aside className="panel p-4 space-y-3 min-w-0">
      <label className="block text-sm">对象类型<select className="input mt-1" value={listType} onChange={e => {
        setListType(e.target.value); setOffset(0); setFocus(undefined); setTrail([]); setRelationshipType(''); setRelationOffset(0);
      }}>{schema.objectTypes.map(type => <option key={type.name} value={type.name}>{objectNames[type.name] || type.name}</option>)}</select></label>
      {rows.map(row => <button className={`btn block w-full text-left break-words ${focus && same(row, focus) ? 'border-indigo-400 bg-indigo-50' : ''}`} key={row.id} onClick={() => navigate(row, true)}>{objectLabel(row)}</button>)}
      {!rows.length && <p className="text-sm text-slate-500">当前页暂无对象</p>}
      <div className="flex gap-2"><button className="btn" disabled={!offset} onClick={() => setOffset(offset - 20)}>上一页</button><button className="btn" disabled={rows.length < 20} onClick={() => setOffset(offset + 20)}>下一页</button></div>
    </aside><section className="panel p-5 min-w-0">
      <nav aria-label="关系浏览路径" className="flex flex-wrap gap-2 text-xs mb-4">{trail.map((item, index) => <button key={index} className="text-indigo-700 text-left break-words max-w-full" onClick={() => {
        setTrail(trail.slice(0, index + 1)); setFocus({ type: item.type, id: item.id }); setListType(item.type); setOffset(0); setRelationshipType(''); setRelationOffset(0);
      }}>{index ? '› ' : ''}{item.label}</button>)}</nav>
      {object && focus ? <>
        <div className="rounded-xl bg-indigo-50 border border-indigo-100 p-4"><span className="text-xs text-indigo-700">{objectNames[object.type] || object.type}</span><h2 className="font-semibold text-lg mt-1 break-words">{objectLabel(object)}</h2>
          {object.presentation?.summary && <p className="text-sm leading-relaxed text-slate-600 mt-3">{object.presentation.summary}</p>}
          <dl className="grid sm:grid-cols-2 gap-3 mt-4 text-sm">{object.presentation?.facts.filter(fact => !fact.target).map(fact => <div key={fact.label}><dt className="text-xs text-slate-500">{fact.label}</dt><dd className="mt-1 break-words">{fact.value}</dd></div>)}</dl>
        </div>
        <div className="flex flex-wrap justify-between items-center gap-3 my-5"><h3 className="font-semibold">相关信息</h3><div className="flex flex-wrap items-center gap-3 text-sm">
          <select aria-label="关联类别" className="input w-auto max-w-full" value={relationshipType} onChange={e => { setRelationshipType(e.target.value); setRelationOffset(0); }}><option value="">全部关联信息</option>{schema.linkTypes.filter(link => link.fromType === focus.type || link.toType === focus.type).map(link => <option key={link.name} value={link.name}>{relationCaption(link.name, link.fromType === focus.type ? 'OUTBOUND' : 'INBOUND', { type: link.fromType === focus.type ? link.toType : link.fromType, properties: {} })}</option>)}</select>
          <label className="flex gap-2 items-center"><input type="checkbox" checked={includeEnded} onChange={e => { setIncludeEnded(e.target.checked); setRelationOffset(0); }} />查看历史关联</label>
        </div></div>
        {relationError && <p role="alert" className="text-red-700">{relationError}</p>}
        {loading ? <p role="status" className="text-sm text-slate-500 py-6">正在读取关系…</p> : <div className="space-y-3">{page.items.map(link => <RelationCard key={`${focus.type}:${focus.id}:${link.type}:${link.id}`} source={object} link={link} navigate={navigate} />)}
          {!page.items.length && !relationError && <p className="p-6 text-center text-slate-500">当前筛选下暂无相关信息，可尝试查看历史关联。</p>}</div>}
        <div className="flex flex-wrap justify-between items-center gap-2 text-xs text-slate-500 mt-4"><span>共 {page.total} 项关联{includeEnded ? '（含历史）' : ''}</span><div className="flex gap-2"><button className="btn" disabled={!relationOffset || loading} onClick={() => setRelationOffset(relationOffset - 20)}>上一页关联</button><button className="btn" disabled={relationOffset + 20 >= page.total || loading} onClick={() => setRelationOffset(relationOffset + 20)}>下一页关联</button></div></div>
        <details className="border-t border-slate-200 pt-4 mt-5"><summary className="cursor-pointer text-sm">对象属性（技术信息）</summary><pre className="bg-slate-50 p-3 mt-3">{JSON.stringify(object, null, 2)}</pre></details>
        <details className="mt-4"><summary className="cursor-pointer text-sm">对象变更记录 · {history.length} 条</summary><Timeline history={history} /></details>
      </> : <p className="text-slate-500 py-8">{focus ? '正在读取对象…' : '选择对象查看事实、关系与历史'}</p>}
    </section></div>
  </main>;
}

function RelationCard({ source, link, navigate }: { source: Obj; link: Relation; navigate: (object: Obj) => void }) {
  const [history, setHistory] = useState<History[]>();
  const [expanded, setExpanded] = useState(false);
  const [error, setError] = useState('');
  const targetLabel = link.target ? objectLabel(link.target) : '对象已删除或不可访问';
  const name = relationCaption(link.type, link.direction, link.target, link.ended);
  async function toggleHistory() {
    setExpanded(!expanded);
    if (!history && !expanded) {
      setError('');
      try { setHistory(await read<History[]>(`/api/objects/${objectPath(source)}/relationships/${encodeURIComponent(link.type)}/${encodeURIComponent(link.id)}/history`)); }
      catch (e) { setError((e as Error).message); }
    }
  }
  return <article aria-label={name} className={`border rounded-xl p-4 ${link.ended ? 'border-amber-200 bg-amber-50/40' : 'border-slate-200'}`}>
    <div className="flex flex-wrap justify-between gap-2 text-xs mb-3"><h4 className="font-semibold text-slate-600">{name}</h4>{link.ended && <span className="badge bg-amber-100 text-amber-800">历史关联</span>}</div>
    <button className="text-indigo-700 font-medium text-left break-words disabled:text-slate-400" disabled={!link.target} onClick={() => { if (link.target) navigate(link.target); }}>{targetLabel}</button>
    {link.targetPresentation?.summary && <p className="text-sm text-slate-500 mt-2 leading-relaxed">{link.targetPresentation.summary}</p>}
    {link.ended && <p className="text-xs text-amber-800 mt-3">此关联已于 {timeLabel(link.validTo)} 结束；关联对象显示当前信息。</p>}
    <div className="flex flex-wrap gap-4 mt-3"><button className="text-xs text-indigo-700" aria-expanded={expanded} onClick={toggleHistory}>{expanded ? '收起关联变更' : '查看关联变更'}</button>
      <details className="text-xs text-slate-500"><summary className="cursor-pointer">技术详情</summary><pre className="bg-slate-50 p-3 mt-2">{relationNames[link.type] || link.type}{'\n'}{link.type} · {link.id}{'\n'}{link.from.type}:{link.from.id} → {link.to.type}:{link.to.id}{'\n'}建立于 {timeLabel(link.validFrom)} · 第 {link.version} 次记录</pre></details>
    </div>
    {expanded && <div className="mt-3"><p className="text-sm font-medium">关联变更记录{history && ` · ${history.length} 条`}</p>{error ? <p role="alert" className="text-red-700 text-sm">{error}</p> : history ? <Timeline history={history} /> : <p className="text-sm text-slate-500">正在读取…</p>}</div>}
  </article>;
}
function Timeline({ history }: { history: History[] }) {
  return <div className="space-y-3 mt-3">{history.map(item => <details key={item.version} className="border-l-2 border-indigo-200 pl-3 text-xs"><summary className="cursor-pointer">{operationNames[item.operation] || item.operation} · 第 {item.version} 次记录<span className="block mt-1 text-slate-500">{timeLabel(item.recordedAt)} · {item.actorId || '系统'}</span></summary><pre className="bg-slate-50 p-3 mt-2">{JSON.stringify(item.state, null, 2)}</pre></details>)}</div>;
}
