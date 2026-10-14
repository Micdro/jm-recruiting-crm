// Makes the invoice PDF for a placement, in the browser. Loaded only when an invoice is made.
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';

const PAGE = { width: 612, height: 792 }; // US Letter
const MARGIN = 54;
const RIGHT = PAGE.width - MARGIN;
const INK = rgb(0x17 / 255, 0x24 / 255, 0x2d / 255);
const INK_2 = rgb(0x48 / 255, 0x57 / 255, 0x5f / 255);
const INK_3 = rgb(0x75 / 255, 0x84 / 255, 0x8b / 255);
const GREEN = rgb(0x1d / 255, 0x6b / 255, 0x57 / 255);
const RULE = rgb(0xdc / 255, 0xe2 / 255, 0xe0 / 255);

const MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' });
const WHOLE_MONEY = new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 });
const LONG_DATE = new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

/** "2026-11-13" as "November 13, 2026". */
export function longDate(iso) {
  if (!iso) return '';
  const [year, month, day] = iso.split('-').map(Number);
  return LONG_DATE.format(new Date(Date.UTC(year, month - 1, day)));
}

/** The file name the invoice downloads as. */
export function invoiceFileName(invoiceNumber, companyName) {
  const safe = `Invoice ${invoiceNumber} ${companyName ?? ''}`.replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim();
  return `${safe}.pdf`;
}

/**
 * The invoice as PDF bytes.
 *
 * @param firm the firm's invoice details (name, address, email, phone, website, payment instructions)
 * @param invoice what was entered for this invoice (number, dates, bill to, description)
 * @param placement the placement it bills (candidate, search, company, start date, salary, fee)
 */
export async function invoicePdf({ firm, invoice, placement }) {
  const doc = await PDFDocument.create();
  doc.setTitle(`Invoice ${invoice.invoiceNumber}`);
  doc.setAuthor(firm.firmName ?? '');
  doc.setCreator('JM CRM');
  const page = doc.addPage([PAGE.width, PAGE.height]);
  const regular = await doc.embedFont(StandardFonts.Helvetica);
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const supported = new Set(regular.getCharacterSet());

  // Helvetica covers Western European text; anything else becomes the nearest plain letter or "?".
  const clean = (value) =>
    [...String(value ?? '')]
      .map((char) => {
        if (char === '\n' || supported.has(char.codePointAt(0))) return char;
        const plain = char.normalize('NFKD').replace(/[̀-ͯ]/g, '');
        return plain && [...plain].every((part) => supported.has(part.codePointAt(0))) ? plain : '?';
      })
      .join('');

  const width = (text, font, size) => font.widthOfTextAtSize(clean(text), size);
  const draw = (text, x, y, { font = regular, size = 10, color = INK, align = 'left' } = {}) => {
    const value = clean(text);
    const left = align === 'right' ? x - font.widthOfTextAtSize(value, size) : x;
    page.drawText(value, { x: left, y, size, font, color });
  };
  /** Breaks text into lines that fit, keeping the line breaks it already has. */
  const wrap = (text, font, size, maxWidth) => {
    const lines = [];
    for (const paragraph of clean(text).split('\n')) {
      let line = '';
      for (const word of paragraph.split(/\s+/).filter(Boolean)) {
        const next = line ? `${line} ${word}` : word;
        if (line && font.widthOfTextAtSize(next, size) > maxWidth) {
          lines.push(line);
          line = word;
        } else {
          line = next;
        }
      }
      lines.push(line);
    }
    return lines;
  };
  const rule = (y, color = RULE, thickness = 0.75) =>
    page.drawLine({ start: { x: MARGIN, y }, end: { x: RIGHT, y }, thickness, color });

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
  const bytes = await invoicePdf(details);
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = invoiceFileName(details.invoice.invoiceNumber, details.placement.companyName);
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
