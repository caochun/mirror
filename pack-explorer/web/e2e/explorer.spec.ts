import { test, expect } from '@playwright/test';

test('generated model, relationship navigation, action authorization and replay', async ({ page }) => {
  const errors:string[]=[];
  page.on('pageerror', e=>errors.push(e.message));
  await page.goto('/');
  await expect(page.getByRole('heading', {name:'Mirror 模型体验台'})).toBeVisible();
  await expect(page.getByText('37 类对象 · 79 类关系 · 14 个 Action', {exact:false})).toBeVisible();
  await expect(page.getByRole('button', {name:/演示人员 · 林青/})).toBeVisible();
  await page.getByRole('button', {name:/^关系/}).click();
  await expect(page.getByRole('button',{name:/组织 · 市级示范单位/})).toBeVisible();
  await page.getByRole('button',{name:/组织 · 市级示范单位/}).click();
  await expect(page.getByRole('heading',{name:/组织 Organization/})).toBeVisible();
  await expect(page.getByRole('heading',{name:'市级示范单位'})).toBeVisible();

  await page.getByRole('button', {name:'人员标签关联 PersonTag',exact:false}).click();
  await expect(page.getByText('NONE',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'人工抑制标签',exact:true}).click();
  await page.getByLabel('note',{exact:true}).fill('浏览器演示：人工删除优先');
  await page.getByRole('checkbox').check();
  await page.getByRole('button',{name:'确认执行',exact:true}).click();
  await expect(page.getByRole('alert')).toContainText('只读预览不能执行');
  await page.getByRole('button',{name:'关闭弹窗'}).click();
  await page.getByLabel('预览身份').selectOption('operator');
  await page.getByRole('button',{name:'人工抑制标签',exact:true}).click();
  await page.getByLabel('note',{exact:true}).fill('浏览器演示：人工删除优先');
  await page.getByRole('checkbox').check();
  await page.getByRole('button',{name:'确认执行',exact:true}).click();
  await expect(page.getByRole('status')).toContainText('Action 已提交');
  const resultText=await page.getByRole('status').innerText();
  const actionId=resultText.match(/"actionId": "([^"]+)"/)![1];
  await page.getByRole('button',{name:'重放同一请求'}).click();
  await expect(page.getByRole('status')).toContainText(actionId);
  await page.getByRole('button',{name:'关闭弹窗'}).click();
  await expect(page.getByText('SUPPRESSED',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:'历史',exact:true}).click();
  await expect(page.getByText('v2 · UPDATED',{exact:true})).toBeVisible();
  await page.getByRole('button',{name:/审计与待投递事件/}).click();
  await expect(page.getByRole('heading',{name:'审计 · 1'})).toBeVisible();
  await expect(page.getByRole('heading',{name:'Outbox · 1'})).toBeVisible();
  await page.getByRole('button',{name:'关闭弹窗'}).click();

  await page.getByRole('button',{name:'模型定义',exact:true}).click();
  await expect(page.getByRole('cell',{name:'suppression',exact:true})).toBeVisible();
  expect(errors).toEqual([]);
  await page.screenshot({path:'test-results/desktop-preview.png',fullPage:true});
});

test('empty object types and mobile layout remain usable', async ({page})=>{
  await page.setViewportSize({width:390,height:844});
  await page.goto('/');
  await page.getByLabel('对象类型',{exact:true}).selectOption('OverdueEpisode');
  await expect(page.getByText('此类型尚无演示记录。可在右侧查看字段及关系定义。')).toBeVisible();
  await page.getByRole('button',{name:'模型定义',exact:true}).click();
  await expect(page.getByRole('cell',{name:'episodeKey',exact:true})).toBeVisible();
  const sizes=await page.evaluate(()=>({width:document.documentElement.clientWidth,scroll:document.documentElement.scrollWidth}));
  expect(sizes.scroll).toBeLessThanOrEqual(sizes.width+1);
  await page.screenshot({path:'test-results/mobile-preview.png',fullPage:true});
});
