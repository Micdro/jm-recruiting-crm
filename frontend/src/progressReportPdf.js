// The progress report for a client as a PDF. Loaded only when a report is made.
import { BRAND, GOLD, INK_2, INK_3, MARGIN, PAGE, RIGHT, RULE, createPdf, fileName, longDate, savePdf } from './pdfText.js';

const BOTTOM = MARGIN + 40; // room for the footer

/**
 * @param firm   the firm's details (name, email, phone, website)
 * @param report date, searchTitle, companyName, preparedFor, openedDate, daysOpen, funnel ([{ label,
 *               count }]), since (a sentence), withClient ([{ who, title, status }]), nextSteps and
 *               asks (a line each)
 */
export async function progressPdf({ firm, report }) {
  const pdf = await createPdf({ title: `Search progress: ${report.searchTitle}`, author: firm.firmName });
  const { doc, bold, regular, draw, wrap, rule, letterhead } = pdf;
  const contentWidth = RIGHT - MARGIN;

  // The firm and the date, top.
  const top = PAGE.height - MARGIN - 10;
  draw('Search progress', RIGHT, top - 2, { font: bold, size: 20, color: BRAND, align: 'right' });
  draw(longDate(report.date), RIGHT, top - 26, { size: 9, color: INK_3, align: 'right' });
  let y = letterhead(firm.firmName);
  const contact = [firm.website, firm.email, firm.phone].filter(Boolean).join('   ');
  if (contact) draw(contact, MARGIN, y, { size: 9, color: INK_3 });
  y -= 24;
  draw(`Prepared for ${[report.preparedFor, report.companyName].filter(Boolean).join(', ')}`, MARGIN, y, { color: INK_2 });
  y -= 14;
  rule(y, GOLD, 1.5);
  y -= 32;

  // The search.
  for (const line of wrap(report.searchTitle, bold, 18, contentWidth)) {
    draw(line, MARGIN, y, { font: bold, size: 18 });
    y -= 22;
  }
  const opened = report.openedDate
    ? ` Open since ${longDate(report.openedDate)}${report.daysOpen > 0 ? `, ${report.daysOpen} ${report.daysOpen === 1 ? 'day' : 'days'}` : ''}.`
    : '';
  for (const line of wrap(`${report.companyName}.${opened}`, regular, 11, contentWidth)) {
    draw(line, MARGIN, y, { size: 11, color: INK_2 });
    y -= 15;
  }

  /** Room for {@code height} more points on this page, or a new page. */
  const room = (height) => {
    if (y - height < BOTTOM) y = pdf.newPage();
  };
  const heading = (text) => {
    y -= 14;
    room(46);
    draw(text, MARGIN, y, { font: bold, size: 11, color: BRAND });
    y -= 17;
  };
  const paragraph = (text, { font = regular, indent = 0 } = {}) => {
    for (const line of wrap(text, font, 10.5, contentWidth - indent)) {
      room(14.5);
      if (line) draw(line, MARGIN + indent, y, { font, size: 10.5 });
      y -= 14.5;
    }
  };
  const bullets = (text, font = regular) => {
    for (const item of String(text ?? '').split('\n').map((line) => line.trim()).filter(Boolean)) {
      room(14.5);
      draw('•', MARGIN + 2, y, { font, size: 10.5, color: BRAND });
      paragraph(item, { font, indent: 14 });
      y -= 3;
    }
  };

  // Where it stands: a bar for each stage, measured against everyone identified.
  heading('Where the search stands');
  const labelWidth = 120;
  const barLeft = MARGIN + labelWidth;
  const barSpace = contentWidth - labelWidth - 36;
  const most = Math.max(1, ...report.funnel.map((row) => row.count));
  for (const row of report.funnel) {
    room(18);
    draw(row.label, MARGIN, y, { size: 10.5, color: INK_2 });
    const width = (row.count / most) * barSpace;
    pdf.page.drawRectangle({ x: barLeft, y: y - 2, width: barSpace, height: 11, color: RULE, opacity: 0.45 });
    if (width > 0) pdf.page.drawRectangle({ x: barLeft, y: y - 2, width: Math.max(width, 2), height: 11, color: BRAND });
    draw(String(row.count), barLeft + barSpace + 10, y, { font: bold, size: 10.5 });
    y -= 18;
  }

  heading('Recent progress');
  paragraph(report.since);

  heading('Candidates with you');
  if (report.withClient.length === 0) {
    paragraph('No one has been submitted to you yet.');
  }
  for (const person of report.withClient) {
    room(34);
    const name = person.title ? `${person.who}, ` : person.who;
    draw(name, MARGIN, y, { font: bold, size: 10.5 });
    if (person.title) {
      const rest = wrap(person.title, regular, 10.5, contentWidth - pdf.width(name, bold, 10.5))[0];
      draw(rest, MARGIN + pdf.width(name, bold, 10.5), y, { size: 10.5 });
    }
    y -= 14.5;
    for (const line of wrap(person.status, regular, 10, contentWidth)) {
      room(14);
      draw(line, MARGIN, y, { size: 10, color: INK_2 });
      y -= 14;
    }
    y -= 6;
  }

  // The ask comes before the plan, so it's never pushed onto a second page by it.
  if (report.asks.trim()) {
    heading('What we need from you');
    bullets(report.asks, bold);
  }
  if (report.nextSteps.trim()) {
    heading('Next steps');
    bullets(report.nextSteps);
  }

  // A footer on every page.
  const pages = doc.getPages();
  const title = report.searchTitle.length > 44 ? `${report.searchTitle.slice(0, 42).trim()}...` : report.searchTitle;
  pages.forEach((page, index) => {
    pdf.page = page;
    draw(`Confidential. Prepared for ${report.companyName}.`, MARGIN, MARGIN, { size: 9, color: INK_3 });
    draw(
      `${firm.firmName}  |  ${title}${pages.length > 1 ? `  |  Page ${index + 1} of ${pages.length}` : ''}`,
      RIGHT,
      MARGIN,
      { size: 9, color: INK_3, align: 'right' },
    );
  });

  return doc.save();
}

/** The file name the report downloads as. */
export function progressFileName(report) {
  return fileName(`Search progress ${report.companyName} ${report.searchTitle} ${report.date}`);
}

/** Makes the report and saves it as a file. */
export async function downloadProgress(details) {
  savePdf(await progressPdf(details), progressFileName(details.report));
}
