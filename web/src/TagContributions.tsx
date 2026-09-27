import { useEffect, useState } from 'react';
import { api } from './api';
import { ErrorBox } from './ui';

type Contribution = {
  id: string;
  source: string;
  state: string;
  sourceReference: string;
  effectiveFrom: string;
  effectiveTo: string;
  actorId: string;
  organizationId: string;
  reason: string;
};

const sources: Record<string, string> = {
  MANUAL: '人工赋标',
  RULE: '规则计算',
  AI_REVIEWED: 'AI建议经人工复核',
  STAGE: '专项事项',
};

export function TagContributions({ personId, tagId }: { personId: string; tagId: string }) {
  const [items, setItems] = useState<Contribution[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setError('');
    api<Contribution[]>(`/people/${personId}/tags/${tagId}/contributions`, { signal: controller.signal })
      .then(setItems)
      .catch(cause => {
        if (cause.name !== 'AbortError') setError(cause.message);
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [personId, tagId]);

  return (
    <section aria-label="标签来源依据" className="mt-5 border-t border-slate-200 pt-5">
      <h4 className="text-sm font-semibold">标签来源依据</h4>
      <p className="muted mt-2">来源记录各自保留。人工删除限制整个标签生效，不会抹去原有依据。</p>
      {loading && <p role="status" className="muted mt-3">正在加载来源记录…</p>}
      {error && <ErrorBox message={error} />}
      {!loading && !error && items.length === 0 && (
        <p className="muted mt-3">此标签尚无独立来源记录；旧记录需要核实迁移，不能据此认定没有历史来源。</p>
      )}
      <ul className="mt-3 space-y-3">
        {items.map(item => (
          <li key={item.id} className="rounded-lg border border-slate-200 bg-white p-4 text-sm">
            <div className="flex flex-wrap justify-between gap-2">
              <span className="font-medium">{sources[item.source] ?? item.source}</span>
              <span className="text-slate-500">{item.state === 'ACTIVE' ? '来源仍有效' : '来源已结束'}</span>
            </div>
            <p className="mt-2 text-xs text-slate-500">
              原操作人：{item.actorId || '未记录'} · 来源组织：{item.organizationId || '未记录'}
            </p>
            <p className="mt-1 text-xs text-slate-500">
              生效于 {item.effectiveFrom ? new Date(item.effectiveFrom).toLocaleString('zh-CN') : '待核实'}
            </p>
            {item.reason && <p className="mt-2 text-xs text-slate-600">{item.reason}</p>}
          </li>
        ))}
      </ul>
    </section>
  );
}
