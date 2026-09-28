import { test, expect, type Page } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';

async function startPreview(port = 0): Promise<{process:ChildProcess;url:string;port:number}> {
  const process=spawn('python3',['../../scripts/run-pack-preview.py','--port',String(port)],{stdio:['ignore','pipe','pipe']});
  return new Promise((resolve,reject)=>{
    let output='';
    const timeout=setTimeout(()=>{process.kill();reject(new Error('Preview startup timed out'));},15000);
    process.once('error',error=>{clearTimeout(timeout);reject(error);});
    process.once('exit',code=>{clearTimeout(timeout);reject(new Error(`Preview exited at startup: ${code}`));});
    process.stdout!.on('data',chunk=>{
      output+=chunk.toString();
      const match=output.match(/Mirror Pack preview: (http:\/\/127\.0\.0\.1:(\d+))/);
      if(match){clearTimeout(timeout);resolve({process,url:match[1],port:Number(match[2])});}
    });
    process.stderr!.on('data',()=>{});
  });
}
async function stopPreview(process:ChildProcess) {
  if(process.exitCode!==null||process.signalCode!==null)return;
  await new Promise<void>((resolve,reject)=>{
    const timeout=setTimeout(()=>{process.kill('SIGKILL');reject(new Error('Preview shutdown timed out'));},5000);
    process.once('exit',()=>{clearTimeout(timeout);resolve();});
    process.kill('SIGTERM');
  });
}
async function openSuppression(page:Page,url:string) {
  await page.goto(url);
  await page.getByLabel('预览身份').selectOption('operator');
  await page.getByRole('button',{name:'人员标签关联 PersonTag',exact:false}).click();
  await expect(page.getByText('NONE',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'人工抑制标签',exact:true}).click();
  await expect(page.getByLabel('personTag',{exact:true})).toHaveValue('pt');
  await page.getByRole('checkbox').check();
}
async function noCommittedEvents(page:Page,url:string) {
  const events=await (await page.request.get(url+'/api/events')).json();
  expect(events.audit).toHaveLength(0);
  expect(events.outbox).toHaveLength(0);
}

test('old open form and old replay require reconfirmation after a real process restart',async({page})=>{
  let preview=await startPreview();
  try {
    await openSuppression(page,preview.url);
    let postCount=0;
    page.on('request',request=>{if(request.method()==='POST'&&request.url().includes('/api/actions/'))postCount++;});
    await stopPreview(preview.process);
    preview=await startPreview(preview.port);
    await page.getByRole('button',{name:'确认执行',exact:true}).click();
    await expect(page.getByRole('alert')).toContainText('演示数据与表单已刷新');
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await expect(page.getByRole('button',{name:'确认执行',exact:true})).toBeDisabled();
    expect(postCount).toBe(0);
    await noCommittedEvents(page,preview.url);

    await expect(page.getByLabel('personTag',{exact:true})).toHaveValue('pt');
    await page.getByRole('checkbox').check();
    await page.getByRole('button',{name:'确认执行',exact:true}).click();
    await expect(page.getByRole('status')).toContainText('Action 已提交');
    expect(postCount).toBe(1);

    await stopPreview(preview.process);
    preview=await startPreview(preview.port);
    await page.getByRole('button',{name:'重放同一请求'}).click();
    await expect(page.getByRole('alert')).toContainText('本次操作没有自动重试');
    await expect(page.getByRole('button',{name:'重放同一请求'})).toHaveCount(0);
    await expect(page.getByRole('status')).toHaveCount(0);
    expect(postCount).toBe(1);
    await noCommittedEvents(page,preview.url);
  } finally { await stopPreview(preview.process); }
});

test('restart between preflight and POST recovers the token rejection without resending',async({page})=>{
  let preview=await startPreview();
  try {
    await openSuppression(page,preview.url);
    let posts=0;
    await page.route('**/api/actions/SuppressPersonTag',async route=>{
      posts++;
      if(posts===1){
        await stopPreview(preview.process);
        preview=await startPreview(preview.port);
      }
      await route.continue();
    });
    await page.getByRole('button',{name:'确认执行',exact:true}).click();
    await expect(page.getByRole('alert')).toContainText('演示数据与表单已刷新');
    expect(posts).toBe(1);
    await noCommittedEvents(page,preview.url);
    await expect(page.getByRole('checkbox')).not.toBeChecked();
    await expect(page.getByLabel('personTag',{exact:true})).toHaveValue('pt');
    await page.getByRole('checkbox').check();
    await page.getByRole('button',{name:'确认执行',exact:true}).click();
    await expect(page.getByRole('status')).toContainText('Action 已提交');
    expect(posts).toBe(2);
    const events=await (await page.request.get(preview.url+'/api/events')).json();
    expect(events.audit).toHaveLength(1);
    expect(events.outbox).toHaveLength(1);
  } finally { await stopPreview(preview.process); }
});
