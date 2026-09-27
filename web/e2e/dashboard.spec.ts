import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username = 'unit') {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间', exact: true }).click();
  await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible();
}

test('dashboard requires authentication and follows organization scope through snapshot drilldowns', async ({ page }, testInfo) => {
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: '请先登录查看业务大屏', exact: true })).toBeVisible();
  await expect(page.getByText('48,620', { exact: true })).toHaveCount(0);
  await login(page);
  await page.goto('/dashboard');
  await expect(page.getByText('Mock 业务记录', { exact: true })).toBeVisible();
  const people = page.getByRole('button', { name: '查看有效对象明细', exact: true });
  await expect(people.locator('strong')).toHaveText('16');
  await people.click();
  await expect(page.getByRole('dialog', { name: '有效对象', exact: true })).toBeVisible();
  await expect(page.getByText('明细共 16 条', { exact: true })).toBeVisible();
  await expect(page.getByRole('dialog').getByText('演示人员01', { exact: true })).toBeVisible();
  await expect(page.getByRole('dialog').getByText('演示二局', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '关闭明细', exact: true }).click();
  await page.getByRole('combobox', { name: '对象当前单位', exact: true }).selectOption('demo-child');
  await page.getByRole('button', { name: '应用筛选', exact: true }).click();
  await expect(people.locator('strong')).toHaveText('8');
  await page.screenshot({ path: testInfo.outputPath('scoped-business-dashboard.png'), fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
});

test('dashboard handles real loading failures and reviewer permission denial', async ({ page }) => {
  await login(page);
  let release: () => void = () => {};
  const pending = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/api/metrics?**', async route => {
    await pending;
    await route.fulfill({ status: 503, contentType: 'application/json', body: JSON.stringify({ message: '统计暂不可用' }) });
  });
  await page.goto('/dashboard');
  await expect(page.getByRole('status')).toContainText('正在加载核心价值数据');
  release();
  await expect(page.getByRole('alert')).toContainText('统计暂不可用');
  await page.unroute('**/api/metrics?**');
  await page.getByRole('button', { name: '重新加载统计', exact: true }).click();
  await expect(page.getByRole('button', { name: '查看有效对象明细', exact: true }).locator('strong')).toHaveText('16');
  await page.goto('/');
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜', exact: true })).toBeVisible();
  await login(page, 'reviewer');
  await page.goto('/dashboard');
  await expect(page.getByRole('heading', { name: '当前账号无统计权限', exact: true })).toBeVisible();
  const response = await page.request.get('/api/metrics');
  expect(response.status()).toBe(403);
});
