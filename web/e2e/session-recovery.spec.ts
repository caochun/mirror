import { test, expect } from '@playwright/test';
import { spawn, type ChildProcess } from 'node:child_process';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

async function start(directory: string, port = 0, referenceData = false): Promise<{ process: ChildProcess; url: string; port: number }> {
  const args = ['../scripts/run-mirror.py', '--port', String(port), '--init-local', '--local-dir', directory];
  if (referenceData) args.push('--reference-data');
  const process = spawn('python3', args, { stdio: ['ignore', 'pipe', 'pipe'] });
  return new Promise((resolve, reject) => {
    let output = '';
    const timer = setTimeout(() => { process.kill(); reject(new Error('Mirror startup timed out')); }, 20000);
    process.once('error', failure => { clearTimeout(timer); reject(failure); });
    process.once('exit', code => { clearTimeout(timer); reject(new Error(`Mirror exited ${code}`)); });
    process.stdout!.on('data', chunk => {
      output += chunk.toString();
      const match = output.match(/Mirror: (http:\/\/127\.0\.0\.1:(\d+))/);
      if (match) { clearTimeout(timer); resolve({ process, url: match[1], port: Number(match[2]) }); }
    });
    process.stderr!.on('data', () => {});
  });
}
async function stop(process: ChildProcess) {
  if (process.exitCode !== null || process.signalCode !== null) return;
  await new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => { process.kill('SIGKILL'); reject(new Error('Shutdown timed out')); }, 5000);
    process.once('exit', () => { clearTimeout(timer); resolve(); });
    process.kill('SIGTERM');
  });
}

test('real process restart preserves facts and command replay while requiring a new login', async ({ page }) => {
  const directory = mkdtempSync(join(tmpdir(), 'mirror-restart-'));
  let server = await start(directory);
  async function login() {
    const password = readFileSync(join(directory, 'credentials.txt'), 'utf8').split('\n').find(line => line.startsWith('admin='))!.substring(6);
    await page.getByLabel('账号', { exact: true }).fill('admin');
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Mirror · 人员工作台' })).toBeVisible();
  }
  try {
    await page.goto(server.url);
    await login();
    let session = await (await page.request.get(server.url + '/api/session')).json();
    const body = { name: '重启验收人员', organization: 'org', note: 'explicit restart test' };
    const command = () => page.request.post(server.url + '/api/mirror/commands/RegisterManualPerson', { data: body, headers: { 'X-CSRF-TOKEN': session.csrf, 'Idempotency-Key': 'restart-registration' } });
    const response = await command();
    expect(response.status()).toBe(200);
    const result = await response.json();
    const before = await (await page.request.get(server.url + '/api/events')).json();
    await stop(server.process);
    server = await start(directory, server.port);
    expect((await page.request.get(server.url + '/api/mirror/people')).status()).toBe(401);
    await page.reload();
    await login();
    session = await (await page.request.get(server.url + '/api/session')).json();
    const replay = await command();
    expect(replay.status()).toBe(200);
    expect(await replay.json()).toEqual(result);
    expect(await (await page.request.get(server.url + '/api/events')).json()).toEqual(before);
    await expect(page.getByRole('button', { name: /重启验收人员/ })).toBeVisible();
    await page.getByRole('button', { name: /重启验收人员/ }).click();
    await page.getByText(/^变更记录 ·/).click();
    await expect(page.getByText(/当前单位关系 · 建立/)).toBeVisible();
  } finally { await stop(server.process); rmSync(directory, { recursive: true, force: true }); }
});

test('reference mock data presents effective, suppressed and pending personnel', async ({ page }) => {
  const directory = mkdtempSync(join(tmpdir(), 'mirror-reference-'));
  const server = await start(directory, 0, true);
  try {
    const password = readFileSync(join(directory, 'credentials.txt'), 'utf8').split('\n').find(line => line.startsWith('admin='))!.substring(6);
    await page.goto(server.url);
    await page.getByLabel('账号', { exact: true }).fill('admin');
    await page.getByLabel('密码', { exact: true }).fill(password);
    await page.getByRole('button', { name: '登录', exact: true }).click();
    await expect(page.getByText('可查看人员共 15 人')).toBeVisible();
    await page.getByRole('button', { name: /演示人员001/ }).click();
    await expect(page.getByText('当前有效', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /演示人员003/ }).click();
    await expect(page.getByText('已人工取消', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: /演示人员004/ }).click();
    await expect(page.getByRole('button', { name: '人工赋标', exact: true })).toBeDisabled();
    await page.getByLabel('管理状态筛选').selectOption('PENDING');
    await expect(page.getByText('可查看人员共 3 人')).toBeVisible();
    await page.screenshot({ path: 'test-results/reference-personnel.png', fullPage: true });
    await page.getByLabel('管理状态筛选').selectOption('');
    await page.getByRole('button', { name: /演示人员001/ }).click();
    await page.getByRole('button', { name: '对象关系', exact: true }).click();
    await expect(page.getByRole('heading', { name: '演示人员001', exact: true })).toBeVisible();
    await expect(page.getByRole('article', { name: '数据来源', exact: true })).toBeVisible();
    await page.getByRole('article', { name: '当前任职', exact: true }).getByRole('button', { name: /演示人员001的任职.*综合管理/ }).click();
    await expect(page.getByRole('heading', { name: '演示人员001的任职 · 综合管理（党政办公室）', exact: true })).toBeVisible();
    await expect(page.getByText('演示人员001在党政办公室担任“综合管理”岗位，目前在任。', { exact: true })).toBeVisible();
    await expect(page.getByRole('article', { name: '人员', exact: true }).getByRole('button', { name: '演示人员001', exact: true })).toBeVisible();
    await expect(page.getByRole('article', { name: '任职单位', exact: true }).getByRole('button', { name: '党政办公室', exact: true })).toBeVisible();
    await expect(page.getByText('指向关联对象', { exact: false })).toHaveCount(0);
    await expect(page.getByText('AppointmentPerson', { exact: false }).first()).not.toBeVisible();
    await page.screenshot({ path: 'test-results/appointment-business-meaning.png', fullPage: true });
    await page.getByRole('article', { name: '岗位', exact: true }).getByRole('button', { name: /综合管理/ }).click();
    await expect(page.getByRole('heading', { name: '综合管理', exact: true })).toBeVisible();
    await page.getByRole('navigation', { name: '关系浏览路径' }).getByRole('button', { name: '演示人员001', exact: true }).click();
    await page.getByRole('article', { name: '标签记录', exact: true }).getByRole('button', { name: /演示人员001的.*药品耗材与设备采购及管理.*标签/ }).click();
    await page.getByRole('article', { name: '赋标依据', exact: true }).getByRole('button', { name: /人工赋标依据/ }).click();
    await expect(page.getByRole('article', { name: '依据来源单位', exact: true })).toBeVisible();
    await page.setViewportSize({ width: 390, height: 844 });
    const width = await page.evaluate(() => ({ viewport: document.documentElement.clientWidth, content: document.documentElement.scrollWidth }));
    expect(width.content).toBeLessThanOrEqual(width.viewport + 1);
    await page.screenshot({ path: 'test-results/relationships-mobile.png', fullPage: true });
  } finally { await stop(server.process); rmSync(directory, { recursive: true, force: true }); }
});
