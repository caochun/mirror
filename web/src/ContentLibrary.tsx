import { useEffect, useState } from 'react';
import { api } from './api';
import { ErrorBox, Heading } from './ui';
import { RichTextEditor } from './RichTextEditor';
import type { Tag } from './Tags';

export type ContentExample = {
  id: string;
  version: number;
  title: string;
  category: string;
  state: string;
  contentVersionId: string;
  bodyHtml: string;
  tagIds: string[];
  used: boolean;
};

export function ContentLibrary() {
  const [examples, setExamples] = useState<ContentExample[]>([]);
  const [permissions, setPermissions] = useState<string[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [selected, setSelected] = useState<ContentExample | null>(null);
  const [title, setTitle] = useState('');
  const [category, setCategory] = useState('履责提醒');
  const [body, setBody] = useState('<p></p>');
  const [tagIds, setTagIds] = useState<string[]>([]);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [command, setCommand] = useState<{ request: string; key: string } | null>(null);

  function load() {
    Promise.all([api<ContentExample[]>('/content-examples'), api<string[]>('/auth/permissions'), api<Tag[]>('/tags')])
      .then(([e, p, t]) => {
        setExamples(e);
        setPermissions(p);
        setTags(t.filter((tag) => tag.status === 'ACTIVE'));
      })
      .catch((cause) => setError(cause.message));
  }
  useEffect(load, []);

  function choose(example: ContentExample | null) {
    setSelected(example);
    setTitle(example?.title ?? '');
    setCategory(example?.category ?? '履责提醒');
    setBody(example?.bodyHtml ?? '<p></p>');
    setTagIds(example?.tagIds ?? []);
    setError('');
    setNotice('');
  }

  async function write(path: string, method: string, value: object) {
    setBusy(true);
    setError('');
    const request = JSON.stringify({ path, method, value });
    const key = command?.request === request ? command.key : crypto.randomUUID();
    setCommand({ request, key });
    try {
      await api(path, { method, headers: { 'Idempotency-Key': key }, body: JSON.stringify(value) });
      setSelected(null);
      setTitle('');
      setBody('<p></p>');
      setTagIds([]);
      setNotice('内容示例已保存。任务引用的是独立副本，历史提醒不随本次变更修改。');
      load();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setBusy(false);
    }
  }

  const canConfigure = permissions.includes('CONTENT_CONFIGURE');
  return (
    <>
      <Heading title="内容示例库" subtitle="按适用标签推荐内容，引用后形成任务独立草稿。内容示例自身不走推送审核。" />
      {error && (
        <div className="mb-5">
          <ErrorBox message={error} retry={load} />
        </div>
      )}
      {notice && (
        <p role="status" className="mb-5 rounded-lg bg-emerald-50 p-4 text-sm text-emerald-800">
          {notice}
        </p>
      )}
      <div className="grid gap-6 xl:grid-cols-[320px_minmax(0,1fr)]">
        <section className="panel p-5">
          {canConfigure && (
            <button className="primary mb-4 w-full" onClick={() => choose(null)}>
              新增内容示例
            </button>
          )}
          <ul className="space-y-3">
            {examples.map((example) => (
              <li key={example.id} className="rounded-lg border border-slate-200 p-4">
                <button className="text-left font-medium text-blue-700" onClick={() => choose(example)}>
                  {example.title}
                </button>
                <p className="muted mt-2">
                  {example.category} ·{' '}
                  {example.state === 'ENABLED' ? '启用' : example.state === 'DISABLED' ? '停用' : '草稿'}
                </p>
                {canConfigure && (
                  <button
                    className="mt-3 text-sm text-blue-700"
                    disabled={busy}
                    onClick={() => {
                      void write(`/content-examples/${example.id}/availability`, 'POST', {
                        expectedVersion: example.version,
                        state: example.state === 'ENABLED' ? 'DISABLED' : 'ENABLED',
                      });
                    }}
                  >
                    {example.state === 'ENABLED' ? '停用' : '启用'} {example.title}
                  </button>
                )}
              </li>
            ))}
          </ul>
          {examples.length === 0 && <p className="muted">暂无可用示例。管理员可新增，或在提醒任务中直接编辑正文。</p>}
        </section>
        <section className="panel min-w-0 space-y-5 p-6">
          {canConfigure ? (
            <>
              <h2 className="font-semibold">{selected ? '编辑示例并保存新版本' : '新增示例'}</h2>
              <label className="block text-sm">
                示例标题
                <input
                  className="mt-2 w-full"
                  maxLength={120}
                  value={title}
                  onChange={(e) => setTitle(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                内容分类
                <input
                  className="mt-2 w-full"
                  maxLength={100}
                  value={category}
                  onChange={(e) => setCategory(e.target.value)}
                />
              </label>
              <RichTextEditor value={body} onChange={setBody} onUploadingChange={setUploading} />
              <fieldset>
                <legend className="mb-3 text-sm">适用标签（仅用于推荐，可多选）</legend>
                <div className="flex flex-wrap gap-4">
                  {tags.map((tag) => (
                    <label className="flex items-center gap-2 text-sm" key={tag.id}>
                      <input
                        type="checkbox"
                        checked={tagIds.includes(tag.id)}
                        onChange={() =>
                          setTagIds((ids) =>
                            ids.includes(tag.id) ? ids.filter((id) => id !== tag.id) : [...ids, tag.id],
                          )
                        }
                      />
                      {tag.name}
                    </label>
                  ))}
                </div>
              </fieldset>
              <button
                className="primary"
                disabled={busy || uploading || !title.trim()}
                onClick={() =>
                  write(
                    selected ? `/content-examples/${selected.id}` : '/content-examples',
                    selected ? 'PUT' : 'POST',
                    {
                      expectedVersion: selected?.version ?? 0,
                      title,
                      category,
                      bodyHtml: body,
                      tagIds,
                    },
                  )
                }
              >
                保存示例版本
              </button>
              <p className="muted">
                新示例默认草稿，启用后方可在任务中选择。已引用内容与媒体保留，不提供直接物理删除入口。
              </p>
            </>
          ) : selected ? (
            <>
              <h2 className="font-semibold">{selected.title}</h2>
              <div className="reminder-body" dangerouslySetInnerHTML={{ __html: selected.bodyHtml }} />
              <p className="muted">示例只读。可在创建提醒时引用并编辑任务副本。</p>
            </>
          ) : (
            <p className="muted">请选择一个启用的内容示例进行预览。</p>
          )}
        </section>
      </div>
    </>
  );
}
