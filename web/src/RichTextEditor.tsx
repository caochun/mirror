import { useEffect, useRef, useState } from 'react';
import { EditorContent, useEditor } from '@tiptap/react';
import type { Editor } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';
import Image from '@tiptap/extension-image';
import { api } from './api';

const ControlledImage = Image.extend({
  addAttributes() {
    return {
      ...this.parent?.(),
      'data-media-id': { default: null, parseHTML: (element) => element.getAttribute('data-media-id') },
    };
  },
});

export function RichTextEditor({
  value,
  onChange,
  onUploadingChange,
}: {
  value: string;
  onChange: (html: string) => void;
  onUploadingChange?: (uploading: boolean) => void;
}) {
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [link, setLink] = useState('');
  const editorRef = useRef<Editor | null>(null);
  const editor = useEditor({
    extensions: [
      StarterKit.configure({
        heading: { levels: [2, 3] },
        code: false,
        codeBlock: false,
        horizontalRule: false,
        link: { openOnClick: false, autolink: false },
      }),
      ControlledImage.configure({ allowBase64: false }),
    ],
    content: value,
    editorProps: {
      attributes: { class: 'min-h-48 p-4 outline-none', 'aria-label': '提醒正文', role: 'textbox' },
      handlePaste: (_view, event) => {
        const image = [...(event.clipboardData?.files ?? [])].find((file) => file.type.startsWith('image/'));
        if (!image) {
          const html = event.clipboardData?.getData('text/html');
          if (
            html &&
            [...new DOMParser().parseFromString(html, 'text/html').querySelectorAll('img')].some(
              (img) => !/^\/api\/media\/media-[a-f0-9]{64}$/.test(img.getAttribute('src') ?? ''),
            )
          ) {
            setError('请上传图片文件或直接粘贴图片，不能引用外部图片地址');
            return true;
          }
          return false;
        }
        void upload(image);
        return true;
      },
    },
    onUpdate: ({ editor }) => onChange(editor.getHTML()),
  });
  editorRef.current = editor;

  useEffect(() => {
    if (editor && editor.getHTML() !== value) editor.commands.setContent(value, { emitUpdate: false });
  }, [value, editor]);

  async function upload(file: File) {
    const current = editorRef.current;
    if (!current || (current.getHTML().match(/<img\b/g)?.length ?? 0) >= 6) {
      setError('每条正文最多6张图片');
      return;
    }
    setUploading(true);
    onUploadingChange?.(true);
    setError('');
    try {
      const data = new FormData();
      data.set('file', file);
      const asset = await api<{ id: string }>('/media', {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: data,
      });
      if (!current.isDestroyed)
        current
          .chain()
          .focus()
          .setImage({ src: `/api/media/${asset.id}`, alt: '提醒配图' })
          .run();
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setUploading(false);
      onUploadingChange?.(false);
    }
  }

  if (!editor) return <p role="status">编辑器正在加载…</p>;
  return (
    <div className="rounded-xl border border-slate-200 bg-white">
      <div className="flex flex-wrap gap-2 border-b border-slate-100 p-3">
        <button type="button" className="secondary" onClick={() => editor.chain().focus().toggleBold().run()}>
          加粗
        </button>
        <button type="button" className="secondary" onClick={() => editor.chain().focus().toggleItalic().run()}>
          斜体
        </button>
        <button type="button" className="secondary" onClick={() => editor.chain().focus().toggleBulletList().run()}>
          无序列表
        </button>
        <button type="button" className="secondary" onClick={() => editor.chain().focus().toggleOrderedList().run()}>
          有序列表
        </button>
        <button type="button" className="secondary" onClick={() => editor.chain().focus().undo().run()}>
          撤销
        </button>
        <label className="secondary cursor-pointer">
          {uploading ? '上传中…' : '上传图片'}
          <input
            aria-label="上传提醒图片"
            type="file"
            accept="image/png,image/jpeg"
            className="sr-only"
            disabled={uploading}
            onChange={(event) => {
              const file = event.target.files?.[0];
              if (file) void upload(file);
              event.target.value = '';
            }}
          />
        </label>
      </div>
      <div className="flex flex-wrap gap-2 border-b border-slate-100 p-3">
        <input
          aria-label="正文HTTPS链接"
          placeholder="https://可信域名/页面"
          value={link}
          onChange={(event) => setLink(event.target.value)}
        />
        <button
          type="button"
          className="secondary"
          disabled={!link.startsWith('https://')}
          onClick={() => {
            editor
              .chain()
              .focus()
              .insertContent({ type: 'text', text: link, marks: [{ type: 'link', attrs: { href: link } }] })
              .run();
            setLink('');
          }}
        >
          插入链接
        </button>
        <span className="self-center text-xs text-slate-500">服务端会再次校验链接与图片权限。</span>
      </div>
      {error && (
        <p role="alert" className="px-4 pt-3 text-sm text-red-700">
          {error}
        </p>
      )}
      <EditorContent editor={editor} className="reminder-body" />
      <p className="px-4 pb-3 text-xs text-slate-500">
        可上传或粘贴 JPG/PNG，每张不超过2MB，最多6张。图片提交审核前须逐张确认。
      </p>
    </div>
  );
}
