// Makes the invoice PDF for a placement, in the browser. Loaded only when an invoice is made.
import { GREEN, INK_2, INK_3, MARGIN, PAGE, RIGHT, createPdf, fileName, longDate, savePdf } from './pdfText.js';

export { longDate };

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const WHOLE_MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });

/** The file name the invoice downloads as. */
export function invoiceFileName(invoiceNumber, companyName) {
  return fileName(`Invoice ${invoiceNumber} ${companyName ?? ''}`);
}

/**
 * The invoice as PDF bytes.
 *
 * @param firm the firm's invoice details (name, address, email, phone, website, payment instructions)
 * @param invoice what was entered for this invoice (number, dates, bill to, description)
 * @param placement the placement it bills (candidate, search, company, start date, salary, fee)
 */
export async function invoicePdf({ firm, invoice, placement }) {
  const pdf = await createPdf({ title: `Invoice ${invoice.invoiceNumber}`, author: firm.firmName });
  const { doc, regular, bold, draw, wrap, rule, width } = pdf;

  // From: the firm, top left.
  let y = PAGE.height - MARGIN - 10;
  draw(firm.firmName, MARGIN, y, { font: bold, size: 16 });
  y -= 18;
  const contact = [firm.email, firm.phone, firm.website].filter(Boolean).join('   ');
  for (const line of [...wrap(firm.address ?? '', regular, 10, 250), ...(contact ? [contact] : [])].filter(Boolean)) {
    draw(line, MARGIN, y, { color: INK_2 });
    y -= 13;
  }
  const fromBottom = y;

  // The invoice's own details, top right.
  let right = PAGE.height - MARGIN - 12;
  draw('Invoice', RIGHT, right, { font: bold, size: 24, color: GREEN, align: 'right' });
  right -= 26;
  for (const [label, value] of [
    ['Invoice number', invoice.invoiceNumber],
    ['Invoice date', longDate(invoice.invoiceDate)],
    ['Due date', longDate(invoice.dueDate)],
  ]) {
    draw(value, RIGHT, right, { font: bold, size: 10, align: 'right' });
    draw(label, RIGHT - width(value, bold, 10) - 12, right, { color: INK_3, align: 'right' });
    right -= 15;
  }

  y = Math.min(fromBottom, right) - 18;
  rule(y, GREEN, 1.5);

  // Bill to.
  y -= 26;
  draw('Bill to', MARGIN, y, { size: 9, color: INK_3 });
  y -= 15;
  draw(invoice.billToName, MARGIN, y, { font: bold, size: 11 });
  y -= 14;
  for (const line of [...wrap(invoice.billToAddress ?? '', regular, 10, 280), invoice.billToEmail ?? ''].filter(Boolean)) {
    draw(line, MARGIN, y, { color: INK_2 });
    y -= 13;
  }

  // The fee.
  y -= 22;
  draw('Description', MARGIN, y, { size: 9, color: INK_3 });
  draw('Amount', RIGHT, y, { size: 9, color: INK_3, align: 'right' });
  y -= 8;
  rule(y);
  y -= 18;
  const amount = MONEY.format(Number(placement.feeAmount ?? 0));
  draw(amount, RIGHT, y, { font: bold, size: 11, align: 'right' });
  for (const line of wrap(invoice.description, bold, 11, 360)) {
    draw(line, MARGIN, y, { font: bold, size: 11 });
    y -= 15;
  }
  const details = [
    placement.candidateName && `Candidate: ${placement.candidateName}`,
    placement.searchTitle && `Position: ${placement.searchTitle}${placement.companyName ? `, ${placement.companyName}` : ''}`,
    placement.startDate && `Start date: ${longDate(placement.startDate)}`,
    placement.baseSalary != null &&
      `Fee: ${placement.feePercent != null ? `${Number(placement.feePercent)}% of ` : ''}first-year base salary of ${WHOLE_MONEY.format(placement.baseSalary)}`,
  ].filter(Boolean);
  for (const line of details) {
    draw(line, MARGIN, y, { color: INK_2 });
    y -= 13;
  }
  y -= 10;
  rule(y);
  y -= 24;
  draw(amount, RIGHT, y, { font: bold, size: 14, align: 'right' });
  draw('Total due', RIGHT - width(amount, bold, 14) - 16, y, { font: bold, size: 11, color: INK_2, align: 'right' });

  // How to pay, with a plain call to action.
  y -= 44;
  draw('How to pay', MARGIN, y, { size: 9, color: INK_3 });
  y -= 15;
  if (firm.paymentInstructions) {
    for (const line of wrap(firm.paymentInstructions, regular, 10, RIGHT - MARGIN)) {
      draw(line, MARGIN, y, { color: INK_2 });
      y -= 13;
    }
    y -= 8;
  }
  const ask = `Please pay ${amount} by ${longDate(invoice.dueDate)} and include invoice ${invoice.invoiceNumber} with your payment.`;
  for (const line of wrap(ask, bold, 10, RIGHT - MARGIN)) {
    draw(line, MARGIN, y, { font: bold });
    y -= 13;
  }

  // Footer.
  const questions = firm.email || firm.phone;
  if (questions) {
    draw(`Questions about this invoice? Contact ${questions}.`, MARGIN, MARGIN, { size: 9, color: INK_3 });
  }
  draw(`${firm.firmName ?? ''}  |  Invoice ${invoice.invoiceNumber}`, RIGHT, MARGIN, { size: 9, color: INK_3, align: 'right' });

  return doc.save();
}

/** Makes the invoice and saves it as a file. */
export async function downloadInvoice(details) {
  savePdf(await invoicePdf(details), invoiceFileName(details.invoice.invoiceNumber, details.placement.companyName));
}
