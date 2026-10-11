import { test, expect, uniqueEmail, uniqueName } from '../support/fixtures.js';

test.describe('Import', () => {
  test('imports a buyer list from a CSV file', async ({ page, crm }) => {
    const companyName = uniqueName('CSV Insurer AI');
    const email = uniqueEmail('casey');
    crm.trackName('companies', companyName);

    const csv = [
      'Company,First Name,Last Name,Title,Email,Status,Touches,Next Action Date,Next Action Type',
      `${companyName},Casey,Nguyen,VP Engineering,${email},Touch 2 Sent,2,10/30/2026,LinkedIn`,
    ].join('\n');

    await page.goto('/import');
    await page.getByLabel('CSV file').setInputFiles({
      name: 'buyers.csv',
      mimeType: 'text/csv',
      buffer: Buffer.from(csv),
    });

    await expect(page.getByText('buyers.csv: 1 row, 9 columns.')).toBeVisible();
    await expect(page.getByLabel('Company (required)')).toHaveValue('Company');
    await expect(page.getByLabel('First name')).toHaveValue('First Name');
    await expect(page.getByLabel('Touch 2 Sent')).toHaveValue('IN_SEQUENCE');

    await page.getByRole('button', { name: 'Import 1 row' }).click();
    await expect(page.getByText('Import finished.')).toBeVisible();
    await expect(page.getByText('1 new contact, 0 updated, 0 skipped as duplicates, 1 new company.')).toBeVisible();

    const company = await crm.findCompanyByName(companyName);
    const contacts = await crm.getJson(`/api/contacts?companyId=${company.id}`);
    contacts.forEach((contact) => crm.track('contacts', contact.id));
    expect(contacts[0]).toMatchObject({
      name: 'Casey Nguyen',
      email,
      status: 'IN_SEQUENCE',
      touchCount: 2,
      nextActionDate: '2026-10-30',
      nextActionType: 'LINKEDIN',
    });
  });
});
