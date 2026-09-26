import { createRoot } from 'react-dom/client';
import './style.css';
// Separate build entry. Identity and content API will be connected in the delivery milestone.
createRoot(document.getElementById('root')!).render(<main className="mx-auto max-w-lg px-6 py-16"><p className="eyebrow">廉洁提醒</p><h1 className="mt-4 text-2xl font-semibold">接收端尚未接通</h1><p className="muted mt-4">当前为开发入口，暂不能获取提醒内容或记录阅读。正式接入后请从鹿路通本人消息进入。</p></main>);
