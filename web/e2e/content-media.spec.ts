import { test, expect } from '@playwright/test';
import type { Page } from '@playwright/test';

async function login(page: Page, username: string) {
  await page.goto('/');
  await page.getByLabel('账号', { exact: true }).fill(username);
  await page.getByLabel('密码', { exact: true }).fill('TestOnlyE2e-2026');
  await page.getByRole('button', { name: '进入工作空间' }).click();
  await expect(page.getByRole('button', { name: '退出', exact: true })).toBeVisible();
}

test('admin example with controlled image is copied and each image is confirmed before review', async ({
  page,
}, testInfo) => {
  await login(page, 'admin');
  await page.getByRole('link', { name: '内容示例库', exact: true }).click();
  await page.getByLabel('示例标题', { exact: true }).fill('浏览器受控配图示例');
  await page.getByRole('textbox', { name: '提醒正文', exact: true }).fill('请认真学习本次廉洁提醒。');
  const imageData = await page.evaluate(() => {
    const canvas = document.createElement('canvas');
    canvas.width = 480;
    canvas.height = 240;
    const context = canvas.getContext('2d')!;
    context.fillStyle = '#e8f0ff';
    context.fillRect(0, 0, 480, 240);
    context.fillStyle = '#1d4ed8';
    context.font = 'bold 32px sans-serif';
    context.fillText('MOCK · REMINDER IMAGE', 28, 115);
    context.font = '18px sans-serif';
    context.fillText('Controlled media / test fixture', 28, 157);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  await page
    .getByLabel('上传提醒图片', { exact: true })
    .setInputFiles({ name: 'example.png', mimeType: 'image/png', buffer: Buffer.from(imageData, 'base64') });
  await expect(page.getByRole('textbox', { name: '提醒正文' }).locator('img')).toHaveCount(1);
  await expect
    .poll(() =>
      page
        .getByRole('textbox', { name: '提醒正文' })
        .locator('img')
        .evaluate((image) => (image as HTMLImageElement).naturalWidth),
    )
    .toBe(480);
  await page.getByRole('button', { name: '保存示例版本', exact: true }).click();
  await expect(page.getByRole('button', { name: '启用 浏览器受控配图示例', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '启用 浏览器受控配图示例', exact: true }).click();
  await expect(page.getByRole('button', { name: '停用 浏览器受控配图示例', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '退出', exact: true }).click();
  await expect(page.getByRole('heading', { name: '登录明镜' })).toBeVisible();
  await login(page, 'unit');
  await page.getByRole('link', { name: '提醒任务', exact: true }).click();
  await page.getByRole('link', { name: '创建提醒', exact: true }).click();
  await page.getByLabel('演示一局', { exact: true }).check();
  await page.getByRole('button', { name: '预览接收名单', exact: true }).click();
  await expect(page.getByRole('heading', { name: '名单预览：16 人' })).toBeVisible();
  await page.getByRole('button', { name: '2. 编辑内容', exact: true }).click();
  await page.getByLabel('引用启用的内容示例').selectOption({ label: '浏览器受控配图示例' });
  await expect(page.getByLabel('提醒标题', { exact: true })).toHaveValue('浏览器受控配图示例');
  await page.getByLabel('提醒标题', { exact: true }).fill('引用后的独立配图任务');
  await expect(page.getByRole('textbox', { name: '提醒正文' }).locator('img')).toHaveCount(1);
  await page.getByRole('button', { name: '4. 保存核对', exact: true }).click();
  await page.getByRole('button', { name: '保存草稿并核对', exact: true }).click();
  await expect(page.getByRole('heading', { name: '引用后的独立配图任务', exact: true })).toBeVisible();
  await page.getByRole('checkbox', { name: /我已核对最终内容/ }).check();
  await expect(page.getByRole('button', { name: '确认名单与内容', exact: true })).toBeDisabled();
  await page.getByRole('checkbox', { name: /我已确认第 1 张图片/ }).check();
  await page.getByRole('button', { name: '确认名单与内容', exact: true }).click();
  await expect(page.getByRole('button', { name: '提交审核', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '提交审核', exact: true }).click();
  await page.getByRole('dialog').getByRole('button', { name: '确认操作', exact: true }).click();
  await expect(page.getByText('第 1 轮审核 · 待审核内容和名单为冻结快照。', { exact: true })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('media-review-snapshot.png'), fullPage: true });
  // The global example retains its original title; task edits cannot write back into it.
  const examples = await (await page.request.get('/api/content-examples')).json();
  expect(examples.some((item: { title: string }) => item.title === '浏览器受控配图示例')).toBe(true);
});
