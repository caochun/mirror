import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';

type Field = { name: string; type: string; required: boolean; sensitive?: boolean; immutable?: boolean; primary?: boolean };
type Type = { name: string; properties: Field[]; linkFields: {name:string;linkType:string;type:string}[] };
type Action = { name: string; permission: string; parameters: Field[] };
type Obj = { id:string; type:string; version:number; properties:Record<string,unknown> };
type History = { version:number;operation:string;actorId:string;recordedAt:string;state:Record<string,unknown> };
type Edge = { id:string;type:string;direction:string;target:{type:string;id:string};targetName:string;deleted:boolean;version:number;history:History[] };
type Detail = { object:Obj; relationships:Edge[]; history:History[] };
type Model = { name:string;version:string;token:string;counts:Record<string,number>;generatedParameters:string[];actions:Record<string,unknown>;schema:{namespace:string;objectTypes:Type[];linkTypes:{name:string;fromType:string;toType:string;cardinality:string}[];actionTypes:Action[];enums:Record<string,string[]>} };
const names:Record<string,string> = { Person:'人员',Organization:'组织',Position:'岗位定义',Appointment:'任职',ObjectMembership:'业务准入',ExternalIdentity:'来源身份',ProfileReference:'档案关联',DataIssue:'业务问题',Tag:'标签目录',TagVersion:'标签语义版本',TagPolicy:'标签策略',TagPolicyVersion:'策略发布版本',PersonTag:'人员标签关联',TagContribution:'标签来源贡献',EvaluationBatch:'评估批次',TagEvaluation:'评估证据',TagSuggestion:'AI 建议',SupervisionMatter:'专项事项',MatterStage:'事项阶段',MatterParticipation:'人员参与',RiskSignal:'风险线索',ReminderTask:'提醒任务',ReminderVersion:'提醒内容版本',AudienceSnapshot:'冻结名单',RecipientSnapshot:'名单成员快照',TaskRecipient:'任务接收人',ReviewRound:'审核轮次',ReadReceipt:'首次阅读',DeliveryAttempt:'发送尝试',DeliveryReceipt:'送达回执',Withdrawal:'撤回意图',WithdrawalAttempt:'撤回尝试',WithdrawalReceipt:'撤回回执',OverdueEpisode:'逾期记录',ContentExample:'内容示例',ContentVersion:'内容发布版本',MediaAsset:'媒体资产',DecideObjectMembership:'决定业务准入',RegisterManualOrganization:'人工登记组织',RegisterManualChildOrganization:'人工登记下级组织',RegisterManualPosition:'人工登记岗位',RegisterManualPerson:'人工登记人员（待确认）',RecordManualAppointment:'登记人工任职',TransferCurrentOrganization:'转移当前组织',SuppressPersonTag:'人工抑制标签',AssignManualTag:'人工赋标并记录依据',ApplyManualTagContribution:'追加人工标签依据',RecordMatterParticipation:'登记阶段参与',RecordRiskSignal:'登记风险线索',DecideReminderReview:'记录审核决定',RecordFirstRead:'记录首次阅读' };
const title = (name:string) => names[name] || name;
const format = (value:unknown) => value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value);
const label = (o:Obj) => format(o.properties.name || o.properties.title || o.id);
const pretty = (v:unknown) => JSON.stringify(v,null,2);

class ApiError extends Error {
  constructor(message:string, readonly status:number, readonly code?:string) {
    super(message);
  }
}

async function api<T>(path:string, init?:RequestInit):Promise<T> {
  const response = await fetch(path, init);
  const data = await response.json();
  if (!response.ok) throw new ApiError(data.error || data.result?.errors?.map((e:{message:string})=>e.message).join('; ') || `HTTP ${response.status}`, response.status, data.code);
  return data;
}

function App() {
  const [model,setModel]=useState<Model>();
  const [error,setError]=useState('');
  const [type,setType]=useState('Person');
  const [filter,setFilter]=useState('');
  const [rows,setRows]=useState<Obj[]>([]);
  const [selected,setSelected]=useState('');
  const [detail,setDetail]=useState<Detail>();
  const [tab,setTab]=useState('事实');
  const [offset,setOffset]=useState(0);
  const [more,setMore]=useState(false);
  const [loading,setLoading]=useState(false);
  const [role,setRole]=useState('reader');
  const [action,setAction]=useState<Action>();
  const [events,setEvents]=useState<{audit:unknown[];outbox:unknown[]}>();
  const [refresh,setRefresh]=useState(0);
  const sequence=useRef(0);
  useEffect(()=>{ api<Model>('/api/model').then(setModel).catch(e=>setError(e.message)); },[refresh]);
  useEffect(()=>{
    const seq=++sequence.current;
    setLoading(true);setError('');setDetail(undefined);setSelected('');
    api<{items:Obj[];hasMore:boolean}>(`/api/objects/${type}?offset=${offset}`).then(result=>{
      if(seq!==sequence.current)return;
      setRows(result.items);setMore(result.hasMore);setSelected(pending.current?.type===type?pending.current.id:result.items[0]?.id||'');pending.current=undefined;
    }).catch(e=>{if(seq===sequence.current)setError(e.message);}).finally(()=>{if(seq===sequence.current)setLoading(false);});
  },[type,offset,refresh]);
  useEffect(()=>{
    if(!selected)return;
    let current=true;setDetail(undefined);
    api<Detail>(`/api/objects/${type}/${encodeURIComponent(selected)}`).then(d=>{if(current)setDetail(d);}).catch(e=>{if(current)setError(e.message);});
    return ()=>{current=false;};
  },[selected,type,refresh]);
  function navigate(t:string,id?:string) {
    if(t===type && id){setSelected(id);setTab('事实');return;}
    setType(t);setOffset(0);setTab('事实');
    // Destination selection is applied after its list has loaded.
    if(id) pending.current={type:t,id};
  }
  const pending=useRef<{type:string;id:string} | undefined>(undefined);

  const definition=model?.schema.objectTypes.find(t=>t.name===type);
  const applicable=model?.schema.actionTypes.filter(a=>a.parameters.some(p=>p.type===type)) || [];
  return <div className="min-h-screen flex flex-col">
    <header className="bg-[#14233e] text-white flex flex-wrap items-center justify-between gap-4 px-7 py-5">
      <div className="flex items-center gap-4"><div className="w-10 h-10 bg-indigo-500 rounded-xl flex items-center justify-center text-xl font-semibold">M</div><div><h1 className="font-semibold text-lg tracking-wide">Mirror <span className="font-normal text-slate-400 ml-2">模型体验台</span></h1><p className="text-xs text-slate-400 mt-1">Domain Pack Explorer · 从领域定义认识业务</p></div></div>
      <div className="flex items-center gap-3"><span className="text-xs text-amber-200 border border-amber-200/30 rounded-full px-3 py-1">本地演示 · 合成数据</span><select aria-label="预览身份" className="bg-slate-700 text-white text-sm rounded-lg px-3 py-2" value={role} onChange={e=>setRole(e.target.value)}><option value="reader">只读预览</option><option value="operator">演示操作员</option></select></div>
    </header>
    <div className="border-b bg-white px-7 py-3 flex flex-wrap items-center justify-between gap-3 text-xs text-slate-500"><span>{model?.schema.namespace||'加载模型…'} <span className="mx-2">/</span> {model?.version} <span className="mx-2">·</span> {model?.schema.objectTypes.length||0} 类对象 · {model?.schema.linkTypes.length||0} 类关系 · {model?.schema.actionTypes.length||0} 个 Action</span><div className="flex gap-3"><button onClick={()=>setRefresh(v=>v+1)}>↻ 刷新数据</button><button onClick={()=>api<{audit:unknown[];outbox:unknown[]}>('/api/events').then(setEvents).catch(e=>setError(e.message))}>审计与待投递事件 ↗</button></div></div>
    {error&&<div role="alert" className="mx-6 mt-4 p-3 rounded-lg bg-red-50 text-red-700 text-sm">{error}</div>}
    <div className="flex flex-1 min-h-0">
      <aside className="w-64 shrink-0 bg-white border-r border-slate-200 p-4 hidden md:block">
        <p className="text-xs font-semibold text-slate-400 mb-3 tracking-widest">领域对象</p><input className="input mb-4" aria-label="查找对象类型" placeholder="查找类型 / 名称" value={filter} onChange={e=>setFilter(e.target.value)}/>
        <nav className="space-y-1 max-h-[74vh] overflow-auto">{model?.schema.objectTypes.filter(t=>(title(t.name)+t.name).toLowerCase().includes(filter.toLowerCase())).map(t=><button key={t.name} onClick={()=>navigate(t.name)} className={`w-full rounded-lg p-2.5 text-left flex justify-between gap-2 ${type===t.name?'bg-indigo-50 text-indigo-700':'hover:bg-slate-50 text-slate-600'}`}><span><span className="block text-sm font-medium">{title(t.name)}</span><span className="text-[10px] opacity-60">{t.name}</span></span><span className="self-center text-xs bg-slate-100 rounded px-1.5 py-0.5">{model.counts[t.name]||0}</span></button>)}</nav>
      </aside>
      <main className="flex-1 min-w-0 p-5 lg:p-7">
        <div className="flex items-start justify-between gap-3 mb-5"><div><p className="text-xs text-indigo-500 uppercase tracking-widest mb-2">对象 · 关系 · 变更</p><h2 className="text-2xl font-semibold">{title(type)} <span className="text-sm font-normal text-slate-400 ml-2">{type}</span></h2><p className="text-sm text-slate-500 mt-2">查看当前事实、关联依据和历史；通过已注册的 Action 修改演示数据。</p></div><button className="btn" onClick={()=>setAction(model?.schema.actionTypes[0])}>执行 Action</button></div>
        <select aria-label="对象类型" className="input md:hidden mb-4" value={type} onChange={e=>navigate(e.target.value)}>{model?.schema.objectTypes.map(t=><option key={t.name} value={t.name}>{title(t.name)}</option>)}</select>
        <div className="grid grid-cols-1 xl:grid-cols-[minmax(220px,0.7fr)_minmax(400px,1.5fr)] gap-5">
          <section className="panel overflow-hidden"><div className="px-4 py-3 border-b text-sm font-medium flex justify-between"><span>对象记录</span><span className="text-slate-400">{model?.counts[type]||0}</span></div>
            {loading?<p className="p-6 text-slate-400 text-sm">正在读取…</p>:rows.length===0?<div className="p-6 text-sm text-slate-400">此类型尚无演示记录。可在右侧查看字段及关系定义。</div>:<div className="divide-y divide-slate-100">{rows.map(row=><button key={row.id} onClick={()=>{setSelected(row.id);setTab('事实');}} className={`w-full text-left px-4 py-4 ${selected===row.id?'bg-indigo-50 border-l-2 border-indigo-500':'hover:bg-slate-50 border-l-2 border-transparent'}`}><span className="text-sm font-medium block break-all">{label(row)}</span><span className="text-xs text-slate-400 block mt-1 break-all">{row.id} <span className="float-right">v{row.version}</span></span></button>)}</div>}
            <div className="flex items-center justify-between p-3 border-t text-xs text-slate-400"><button className="btn" disabled={offset===0} onClick={()=>setOffset(o=>Math.max(0,o-100))}>上一页</button><span>{offset/100+1}</span><button className="btn" disabled={!more} onClick={()=>setOffset(o=>o+100)}>下一页</button></div>
          </section>
          <section className="panel min-w-0 overflow-hidden">
            <div className="flex border-b px-4 gap-5">{['事实','关系','历史','模型定义'].map(t=><button key={t} className={`py-4 text-sm border-b-2 ${tab===t?'text-indigo-600 border-indigo-600':'text-slate-400 border-transparent'}`} onClick={()=>setTab(t)}>{t}{t==='关系'&&detail?` · ${detail.relationships.length}`:''}</button>)}</div>
            <div className="p-5">
              {tab==='模型定义'?<><div className="text-xs text-slate-500 mb-3">字段和关联由 ODL 自动生成。敏感字段仅显示保护标记。</div><table className="w-full"><thead><tr><th>字段</th><th>类型</th><th>约束</th></tr></thead><tbody>{definition?.properties.map(p=><tr key={p.name}><td>{p.name}</td><td className="font-mono text-xs">{p.type}{p.required?'!':''}</td><td className="text-xs text-slate-500">{[p.primary?'主键':'',p.immutable?'不可变':'',p.sensitive?'敏感':''].filter(Boolean).join(' · ')}</td></tr>)}</tbody></table><div className="mt-5 text-sm font-medium">关系类型</div>{model?.schema.linkTypes.filter(l=>l.fromType===type||l.toType===type).map(l=><div className="text-xs border-b py-3" key={l.name}><strong>{l.name}</strong><p className="text-slate-400 mt-1">{title(l.fromType)} → {title(l.toType)} · {l.cardinality}</p></div>)}</>:!selected?<div className="text-sm text-slate-400 py-6">选择对象，或切换“模型定义”浏览结构。</div>:!detail?<p className="text-slate-400 text-sm">读取详情…</p>:<>
                {tab==='事实'&&<><div className="mb-4 flex justify-between items-center"><h3 className="font-semibold">{label(detail.object)}</h3><span className="badge">技术版本 {detail.object.version}</span></div><dl className="divide-y divide-slate-100">{definition?.properties.filter(p=>!p.primary).map(p=><div className="grid grid-cols-[minmax(100px,0.7fr)_minmax(130px,1.3fr)] gap-4 py-3 text-sm" key={p.name}><dt className="text-slate-500">{p.name}</dt><dd className="break-all">{p.sensitive?<span className="text-slate-400">受保护字段</span>:typeof detail.object.properties[p.name]==='object'&&detail.object.properties[p.name]!==null?<pre>{pretty(detail.object.properties[p.name])}</pre>:format(detail.object.properties[p.name])}</dd></div>)}</dl><div className="mt-5 border-t pt-4"><p className="text-xs text-slate-400 mb-3">关联 Action</p><div className="flex flex-wrap gap-2">{applicable.length?applicable.map(a=><button key={a.name} className="btn" onClick={()=>setAction(a)}>{title(a.name)}</button>):<span className="text-xs text-slate-400">当前类型没有已注册 Action</span>}</div></div></>}
                {tab==='关系'&&<div className="space-y-3">{detail.relationships.length===0?<p className="text-sm text-slate-400">暂无关系记录</p>:detail.relationships.map(e=><div key={e.type+e.id+e.direction} className="border border-slate-200 rounded-lg p-3"><div className="flex justify-between gap-2"><span className="text-xs text-indigo-500">{e.direction==='OUTBOUND'?'指向 →':'来自 ←'} {e.type}</span><span className={`badge ${e.deleted?'text-amber-700 bg-amber-50':''}`}>{e.deleted?'已结束':'当前有效'}</span></div><button className="block text-left font-medium text-sm mt-2 hover:text-indigo-600" onClick={()=>navigate(e.target.type,e.target.id)}>{title(e.target.type)} · {e.targetName} ↗</button><details className="mt-2 text-xs text-slate-400"><summary>关系历史 · {e.history.length} 条</summary><Timeline history={e.history}/></details></div>)}</div>}
                {tab==='历史'&&<Timeline history={detail.history}/>}
              </>}
            </div>
          </section>
        </div>
        <p className="text-xs text-slate-400 mt-5 leading-relaxed">数据仅保存在本进程内存，重启恢复合成样例。演示操作员使用示范单位、本地模拟本人身份；不调用鹿路通或其他外部渠道。关系每类最多展示 100 条。</p>
      </main>
    </div>
    {action&&model&&<ActionForm key={action.name} model={model} action={action} role={role} current={detail?.object} choose={setAction} close={()=>setAction(undefined)} changed={()=>setRefresh(v=>v+1)} sessionChanged={latest=>{
      setModel(latest);
      setAction(previous=>latest.schema.actionTypes.find(a=>a.name===previous?.name));
      setRefresh(v=>v+1);
    }}/>}
    {events&&<Modal title="审计与待投递事件" close={()=>setEvents(undefined)}><p className="text-sm text-slate-500 mb-4">Action 成功记录与事实同事务提交。此体验台不启动外部事件投递器。</p><h3 className="font-medium">审计 · {events.audit.length}</h3><pre className="bg-slate-50 rounded-lg p-3 my-3">{pretty(events.audit)}</pre><h3 className="font-medium">Outbox · {events.outbox.length}</h3><pre className="bg-slate-50 rounded-lg p-3 my-3">{pretty(events.outbox)}</pre></Modal>}
  </div>;
}

function Timeline({history}:{history:History[]}) {
  return <div className="space-y-3 mt-3">{history.map(h=><details key={h.version} className="border-l-2 border-indigo-200 pl-4 py-1"><summary className="cursor-pointer text-sm"><span className="font-medium">v{h.version} · {h.operation}</span><span className="block text-xs text-slate-400 mt-1">{h.recordedAt} · {h.actorId}</span></summary><pre className="bg-slate-50 rounded-lg p-3 mt-2">{pretty(h.state)}</pre></details>)}</div>;
}
function Modal({title,close,children}:{title:string;close:()=>void;children:React.ReactNode}) {
  const element=useRef<HTMLDivElement>(null);
  useEffect(()=>{const previous=document.activeElement as HTMLElement;const nodes=()=>Array.from(element.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input,select,textarea,summary,[tabindex="0"]')||[]);nodes()[0]?.focus();const handler=(e:KeyboardEvent)=>{if(e.key==='Escape')close();if(e.key==='Tab'){const all=nodes();if(e.shiftKey&&document.activeElement===all[0]){e.preventDefault();all.at(-1)?.focus();}else if(!e.shiftKey&&document.activeElement===all.at(-1)){e.preventDefault();all[0]?.focus();}}};document.addEventListener('keydown',handler);return()=>{document.removeEventListener('keydown',handler);previous?.focus();};},[]);
  return <div className="fixed inset-0 z-20 bg-slate-950/45 flex items-center justify-center p-3" onMouseDown={e=>{if(e.target===e.currentTarget)close();}}><div ref={element} role="dialog" aria-modal="true" aria-label={title} className="bg-white rounded-2xl shadow-2xl w-full max-w-3xl max-h-[92vh] overflow-auto"><div className="sticky top-0 bg-white border-b px-6 py-4 flex items-center justify-between z-10"><h2 className="font-semibold">{title}</h2><button aria-label="关闭弹窗" className="btn" onClick={close}>关闭 ×</button></div><div className="p-6">{children}</div></div></div>;
}

function ActionForm({model:initialModel,action,role,current,choose,close,changed,sessionChanged}:{model:Model;action:Action;role:string;current?:Obj;choose:(a:Action)=>void;close:()=>void;changed:()=>void;sessionChanged:(model:Model)=>void}) {
  // Pin the form to the server instance whose objects were displayed.
  // A background metadata refresh must not silently authorize old inputs in a new in-memory database.
  const [model,setModel]=useState(initialModel);
  const [loading,setLoading]=useState(true);
  const [options,setOptions]=useState<Record<string,Obj[]>>({});
  const [values,setValues]=useState<Record<string,string>>({});
  const [result,setResult]=useState<unknown>();
  const [error,setError]=useState('');
  const [busy,setBusy]=useState(false);
  const [confirm,setConfirm]=useState(false);
  const [request,setRequest]=useState(crypto.randomUUID());
  const [last,setLast]=useState<string>();
  const generated=new Set([...model.generatedParameters,'personTagId','pairKey']);
  const fields=action.parameters.filter(p=>!generated.has(p.name));
  const types=new Set(model.schema.objectTypes.map(t=>t.name));
  useEffect(()=>{
    let live=true;
    setLoading(true);
    const needed=[...new Set(fields.map(p=>p.type).filter(t=>types.has(t)))];
    Promise.all(needed.map(async type=>[type,(await api<{items:Obj[]}>(`/api/objects/${type}`)).items] as const)).then(entries=>{
      if(!live)return;
      const all=Object.fromEntries(entries);setOptions(all);
      const v:Record<string,string>={};
      for(const p of fields){
        if(types.has(p.type)) v[p.name]=current?.type===p.type?current.id:all[p.type]?.[0]?.id||'';
        else if(model.schema.enums[p.type]) v[p.name]=model.schema.enums[p.type][0];
        else if(p.type==='DateTime') v[p.name]=new Date().toISOString();
        else if(p.name==='expectedVersion') v[p.name]='1';
        else if(p.name==='decisionCode') v[p.name]='DEMO_REVIEW';
        else if(p.name==='sourceSystem') v[p.name]='demo-source';
        else if(p.name==='sourceKey') v[p.name]='demo-'+crypto.randomUUID();
        else if(p.name==='renderedContentHash') v[p.name]='hash';
        else v[p.name]='';
      }
      // Review defaults to approved; membership to in-scope; read defaults to current published demo version.
      if(action.name==='DecideObjectMembership')v.status='IN_SCOPE';
      if(action.name==='DecideObjectMembership')v.decisionCode='MANUAL_CONFIRMATION';
      if(action.name==='RegisterManualPerson')v.name='演示人员 · 人工登记';
      if(action.name==='RegisterManualOrganization')v.name='演示新增单位';
      if(action.name==='RegisterManualChildOrganization')v.name='演示新增下级单位';
      if(action.name==='RegisterManualPosition')v.name='演示新增岗位';
      if(action.name==='AssignManualTag')v.note='操作人确认后的人工标签依据';
      if(action.name==='RecordManualAppointment')v.startedOn=new Date().toISOString().slice(0,10);
      if(action.name==='RecordFirstRead'&&all.ReminderVersion?.some(o=>o.id==='published'))v.version='published';
      const first=action.name==='RecordFirstRead'?fields.find(p=>p.name==='version'):fields.find(p=>types.has(p.type));
      if(first&&v.expectedVersion)v.expectedVersion=String(all[first.type]?.find(o=>o.id===v[first.name])?.version||1);
      setValues(v);
    }).catch(e=>{if(live)setError(e.message);}).finally(()=>{if(live)setLoading(false);});
    return ()=>{live=false;};
  },[action.name,model.token]);
  function update(p:Field,value:string){setValues(old=>{const v={...old,[p.name]:value};const first=fields.find(f=>types.has(f.type));const versionTarget=action.name==='RecordFirstRead'?'version':first?.name;if(p.name===versionTarget&&v.expectedVersion)v.expectedVersion=String(options[p.type]?.find(o=>o.id===value)?.version||1);if(p.name==='version'){const record=options[p.type]?.find(o=>o.id===value);v.renderedContentHash=String(record?.properties.contentHash||'');}return v;});setConfirm(false);setLast(undefined);setRequest(crypto.randomUUID());setResult(undefined);}
  function renewSession(latest:Model) {
    setConfirm(false);
    setLast(undefined);
    setResult(undefined);
    setRequest(crypto.randomUUID());
    setOptions({});
    setValues({});
    setLoading(true);
    setModel(latest);
    sessionChanged(latest);
    setError('预览服务已重启或会话已更新，演示数据与表单已刷新。请重新检查参数并确认；本次操作没有自动重试。');
  }
  async function execute(replay=false){
    if(busy||loading)return;
    setError('');setBusy(true);
    try{
      const latest=await api<Model>('/api/model',{cache:'no-store'});
      if(latest.token!==model.token) {
        renewSession(latest);
        return;
      }
      const body:Record<string,unknown>={};
      for(const p of fields){const value=values[p.name];if(!value){if(p.required)throw new Error(`请填写 ${p.name}`);body[p.name]=null;continue;}body[p.name]=p.type==='Int'||p.type==='Float'?Number(value):p.type==='Boolean'?value==='true':p.type==='JSON'||p.type.startsWith('[')?JSON.parse(value):value;}
      const serialized=replay?last!:JSON.stringify(body);setLast(serialized);
      const data=await api(`/api/actions/${action.name}`,{method:'POST',headers:{'Content-Type':'application/json','X-Preview-Token':model.token,'X-Preview-Role':role,'Idempotency-Key':request},body:serialized});
      setResult(data);setConfirm(false);changed();
    }catch(e){
      // The process may restart between the preflight check and POST.
      // Only this explicit pre-execution rejection is eligible for session recovery; never retry network errors or generic denials.
      if(e instanceof ApiError && e.status===403 && e.code==='DENIED' && e.message==='Preview token required') {
        setConfirm(false);
        setLast(undefined);
        try {
          const latest=await api<Model>('/api/model',{cache:'no-store'});
          if(latest.token!==model.token) renewSession(latest);
          else setError('预览凭据校验失败，操作未执行。请刷新页面后重新确认。');
        } catch {
          setError('预览连接已失效，暂时无法刷新凭据。请等待服务恢复后刷新页面。');
        }
      } else setError(e instanceof Error?e.message:String(e));
    }finally{setBusy(false);}
  }
  return <Modal title="执行领域 Action" close={close}><select className="input mb-4" aria-label="选择 Action" value={action.name} onChange={e=>choose(model.schema.actionTypes.find(a=>a.name===e.target.value)!)}>{model.schema.actionTypes.map(a=><option value={a.name} key={a.name}>{title(a.name)} · {a.name}</option>)}</select><p className="text-xs text-slate-500 mb-4">权限契约：<span className="badge">{action.permission}</span> · 当前为{role==='reader'?'只读预览（执行会被拒绝）':'演示操作员'}</p>
    <fieldset disabled={busy||loading} className="grid sm:grid-cols-2 gap-4">{fields.map(p=><label className="block text-sm" key={p.name}><span className="block mb-1.5 text-slate-600">{p.name} {p.required&&<span className="text-indigo-500">*</span>} <span className="text-xs text-slate-400">{p.type}</span></span>{types.has(p.type)?<select aria-label={p.name} className="input" value={values[p.name]||''} onChange={e=>update(p,e.target.value)}><option value="">选择对象</option>{options[p.type]?.map(o=><option key={o.id} value={o.id}>{label(o)} · {o.id} · v{o.version}</option>)}</select>:model.schema.enums[p.type]||p.type==='Boolean'?<select aria-label={p.name} className="input" value={values[p.name]||''} onChange={e=>update(p,e.target.value)}>{!p.required&&<option value="">未提供</option>}{(model.schema.enums[p.type]||['true','false']).map(v=><option key={v}>{v}</option>)}</select>:p.type==='JSON'||p.type.startsWith('[')?<textarea aria-label={p.name} className="input" value={values[p.name]||''} onChange={e=>update(p,e.target.value)} rows={3}/>:<input aria-label={p.name} className="input" type={p.type==='Int'||p.type==='Float'?'number':'text'} value={values[p.name]||''} onChange={e=>update(p,e.target.value)} placeholder={p.required?'必填':'可选'}/>}</label>)}</fieldset>
    <details className="my-5 text-sm text-slate-500"><summary className="cursor-pointer">查看 Pack 定义的前置条件与事务效果</summary><pre className="p-3 bg-slate-50 mt-3 rounded-lg">{pretty(model.actions[action.name])}</pre></details>
    <p className="text-xs text-slate-400 mb-3">系统派生身份由服务端生成。选择对象后带入技术版本，可手动修改 expectedVersion 验证冲突拒绝。只读身份也可提交以观察授权拒绝。</p>
    {error&&<div role="alert" className="p-3 bg-red-50 text-red-700 rounded-lg text-sm mb-4 break-all">{error}</div>}
    {result!==undefined&&<div role="status" className="bg-emerald-50 border border-emerald-200 rounded-xl p-4 mb-4"><p className="text-emerald-700 font-medium text-sm">Action 已提交，可查看对象历史与审计事件</p><pre className="mt-3">{pretty(result)}</pre></div>}
    <label className="flex items-center gap-2 text-sm my-4"><input type="checkbox" disabled={busy||loading} checked={confirm} onChange={e=>setConfirm(e.target.checked)}/>确认对合成演示数据执行此 Action</label>
    <div className="flex flex-wrap gap-3"><button className="btn primary" disabled={!confirm||busy||loading} onClick={()=>execute()}>{busy?'执行中…':'确认执行'}</button>{last&&<button className="btn" disabled={busy||loading} onClick={()=>execute(true)}>重放同一请求</button>}<span className="text-[10px] text-slate-400 self-center break-all">请求标识 {request}</span></div>
  </Modal>;
}

createRoot(document.getElementById('root')!).render(<App/>);
