import { test, expect, companyPayload, uniqueName } from '../support/fixtures.js';

test.describe('Recent changes', () => {
  test('an edit from earlier can be undone from Recent changes', async ({ page, crm }) => {
    const company = await crm.createCompany();
    const renamed = uniqueName('Renamed AI');
    const edited = await crm.api.put(`/api/companies/${company.id}`, {
      data: companyPayload({ name: renamed, location: 'Chicago, IL' }),
    });
    expect(edited.ok(), await edited.text()).toBeTruthy();

    await page.goto('/');
    await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Recent changes', exact: true }).click();
    const item = page.getByRole('listitem').filter({ hasText: `Edited ${renamed}` });
    await expect(item.getByRole('link', { name: `Edited ${renamed}` })).toHaveAttribute('href', `/companies/${company.id}`);

    await item.getByRole('button', { name: 'Undo', exact: true }).click();
    await item.getByRole('button', { name: 'Undo it' }).click();
    await expect(page.getByRole('status')).toContainText(`Undone: Edited ${renamed}`);
    await expect(item).toContainText('Undone');

    const back = await crm.getJson(`/api/companies/${company.id}`);
    expect(back.name).toBe(company.name);
    expect(back.location).toBe(company.location);
  });
});
