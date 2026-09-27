import { useEffect } from 'react';
import { useEditor, EditorContent } from '@tiptap/react';
import StarterKit from '@tiptap/starter-kit';

export function RichTextEditor({ value, onChange }: { value: string; onChange: (html: string) => void }) {
  const editor = useEditor({
    extensions: [
      StarterKit.configure({ heading: { levels: [2, 3] }, code: false, codeBlock: false, horizontalRule: false }),
    ],
    content: value,
    editorProps: { attributes: { class: 'min-h-48 p-4 outline-none', 'aria-label': '提醒正文', role: 'textbox' } },
    onUpdate: ({ editor }) => onChange(editor.getHTML()),
  });
  useEffect(() => {
    if (editor && editor.getHTML() !== value) editor.commands.setContent(value, { emitUpdate: false });
  }, [value, editor]);
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
      </div>
      <EditorContent editor={editor} className="reminder-body" />
    </div>
  );
}
