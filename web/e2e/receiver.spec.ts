import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function post(page: Page, path: string, body: object) {
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  const response = await page.request.post(`/api${path}`, {
    data: body, headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
  });
  expect(response.ok(), await response.text()).toBeTruthy();
  return response.json();
}

async function prepare(page: Page, title: string, image: boolean) {
  await page.goto('/');
  await post(page, '/auth/login', { username: 'unit', password: 'TestOnlyE2e-2026' });
  let body = '<p>浏览器正文加载验证</p>';
  const confirmations: string[] = [];
  if (image) {
    const bytes = await page.evaluate(() => {
      const canvas = document.createElement('canvas');
      canvas.width = 16; canvas.height = 16;
      canvas.getContext('2d')!.fillRect(0, 0, 16, 16);
      return canvas.toDataURL('image/png').split(',')[1];
    });
    const csrf = await (await page.request.get('/api/auth/csrf')).json();
    const uploaded = await page.request.post('/api/media', {
      multipart: { file: { name: 'pixel.png', mimeType: 'image/png', buffer: Buffer.from(bytes, 'base64') } },
      headers: { [csrf.headerName]: csrf.token, 'Idempotency-Key': crypto.randomUUID() },
    });
    expect(uploaded.ok(), await uploaded.text()).toBeTruthy();
    const asset = await uploaded.json();
    body += `<img data-media-id="${asset.id}" alt="图片失败验证">`;
    confirmations.push(`${asset.id}:0:${asset.digest}`);
  }
  const saved = await post(page, '/reminders', { expectedVersion: 0, title, bodyHtml: body, category: '履责', readingWindow: '1d',
    restoreIds: [], filter: { organizationIds: [], tagIds: [], tagOperator: 'ANY', personIds: ['demo-person-001'], excludedIds: [] } });
  const confirmed = await post(page, `/reminders/${saved.id}/confirm`, { expectedVersion: saved.version, selectionDigest: saved.selectionDigest,
    recipientCount: 1, singleRecipientAcknowledged: true, contentDigest: saved.contentDigest, contentAcknowledged: true,
    duplicateAcknowledged: true, confirmedMediaDigests: confirmations });
  const submitted = await post(page, `/reminders/${saved.id}/submit`, { expectedVersion: confirmed.version });
  await post(page, '/auth/login', { username: 'reviewer', password: 'TestOnlyE2e-2026' });
  const approved = await post(page, `/reminders/${saved.id}/review`, { expectedVersion: submitted.version, decision: 'APPROVE', comment: '核对通过' });
  await post(page, '/auth/login', { username: 'unit', password: 'TestOnlyE2e-2026' });
  await post(page, `/reminders/${saved.id}/mock-dispatch`, { expectedVersion: approved.version });
  const delivery = await (await page.request.get(`/api/reminders/${saved.id}/delivery`)).json();
  const entry = await post(page, `/reminders/${saved.id}/recipients/${delivery.recipients[0].id}/mock-entry`, {});
  return { task: saved.id, url: entry.url };
}

test('a failed image gets a placeholder but the rendered body still records its first read', async ({ page }, testInfo) => {
  const task = await prepare(page, '图片访问失败验证', true);
  await page.route('**/api/receiver/*/media/**', route => route.fulfill({ status: 503, body: 'unavailable' }));
  await page.goto(task.url);
  await expect(page.getByRole('heading', { name: '图片访问失败验证', exact: true })).toBeVisible();
  await expect(page.getByText('图片暂无法加载，正文仍可正常阅读。', { exact: true })).toBeVisible();
  await expect(page.getByText(/已记录本版本首次阅读/)).toBeVisible();
  const delivery = await (await page.request.get(`/api/reminders/${task.task}/delivery`)).json();
  expect(delivery.recipients[0].readState).toBe('READ');
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath('receiver-image-failed.png'), fullPage: true });
});

test('content failure never records reading and another browser cannot reuse the receiver link', async ({ page, browser }) => {
  const task = await prepare(page, '正文访问失败验证', false);
  let readRequests = 0;
  page.on('request', request => { if (/\/receiver\/[^/]+\/read$/.test(request.url())) readRequests++; });
  await page.route('**/api/receiver/*/content', route => route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '正文暂不可用' }) }));
  await page.goto(task.url);
  await expect(page.getByRole('heading', { name: '暂时无法查看提醒', exact: true })).toBeVisible();
  const delivery = await (await page.request.get(`/api/reminders/${task.task}/delivery`)).json();
  expect(delivery.recipients[0].readState).toBe('UNREAD');
  expect(readRequests).toBe(0);
  const copiedUrl = page.url();
  const other = await browser.newContext();
  const forwarded = await other.newPage();
  await forwarded.goto(copiedUrl);
  await expect(forwarded.getByText('本人会话已失效，请从本人消息入口重新打开。', { exact: true })).toBeVisible();
  await other.close();
  await page.unroute('**/api/receiver/*/content');
  await page.getByRole('button', { name: '重新加载', exact: true }).click();
  await expect(page.getByText(/已记录本版本首次阅读/)).toBeVisible();
});
