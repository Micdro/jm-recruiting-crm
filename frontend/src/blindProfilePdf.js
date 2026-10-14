// A one-page (or longer) candidate profile without the candidate's identity, for a client.
// Loaded only when a profile is made.
import { GREEN, INK_2, INK_3, MARGIN, PAGE, RIGHT, createPdf, fileName, longDate, savePdf } from './pdfText.js';

const BOTTOM = MARGIN + 40; // room for the footer

/**
 * @param firm the firm's details (name, email, phone, website)
 * @param profile reference, date, preparedFor, title, employer, location, facts ([label, value]),
 *                evidence and why, all as they should appear
 */
export async function profilePdf({ firm, profile }) {
  const pdf = await createPdf({ title: `Candidate profile ${profile.reference}`, author: firm.firmName });
  const { doc, bold, regular, draw, wrap, rule } = pdf;
  const contentWidth = RIGHT - MARGIN;

  // The firm and the reference, top.
  let y = PAGE.height - MARGIN - 10;
  draw(firm.firmName, MARGIN, y, { font: bold, size: 13 });
  draw('Candidate profile', RIGHT, y - 2, { font: bold, size: 20, color: GREEN, align: 'right' });
  y -= 16;
  const contact = [firm.website, firm.email, firm.phone].filter(Boolean).join('   ');
  if (contact) draw(contact, MARGIN, y, { size: 9, color: INK_3 });
  y -= 2;
  draw(`Ref. ${profile.reference}   ${longDate(profile.date)}`, RIGHT, y - 8, { size: 9, color: INK_3, align: 'right' });
  y -= 22;
  if (profile.preparedFor) {
    draw(`Prepared for ${profile.preparedFor}`, MARGIN, y, { color: INK_2 });
    y -= 14;
  }
  rule(y, GREEN, 1.5);
  y -= 32;

  // Who they are, without saying who.
  for (const line of wrap(profile.title || 'Candidate', bold, 18, contentWidth)) {
    draw(line, MARGIN, y, { font: bold, size: 18 });
    y -= 22;
  }
  const where = [profile.employer && `Currently at ${profile.employer}.`, profile.location && `Based in ${profile.location}.`]
    .filter(Boolean)
    .join(' ');
  for (const line of wrap(where, regular, 11, contentWidth)) {
    if (!line) continue;
    draw(line, MARGIN, y, { size: 11, color: INK_2 });
    y -= 15;
  }
  y -= 14;

  // The facts, two to a row.
  const column = (contentWidth - 24) / 2;
  const facts = profile.facts.filter(([, value]) => value);
  for (let index = 0; index < facts.length; index += 2) {
    const row = facts.slice(index, index + 2);
    let tallest = 0;
    row.forEach(([label, value], position) => {
      const x = MARGIN + position * (column + 24);
      draw(label, x, y, { size: 9, color: INK_3 });
      const lines = wrap(value, regular, 10.5, column);
      lines.forEach((line, number) => draw(line, x, y - 14 - number * 13.5, { size: 10.5 }));
      tallest = Math.max(tallest, lines.length);
    });
    y -= 14 + tallest * 13.5 + 10;
  }

  /** Room for {@code height} more points on this page, or a new page. */
  const room = (height) => {
    if (y - height < BOTTOM) y = pdf.newPage();
  };

  const section = (heading, text) => {
    if (!text || !text.trim()) return;
    y -= 10;
    room(40);
    draw(heading, MARGIN, y, { font: bold, size: 11, color: GREEN });
    y -= 18;
    for (const line of wrap(text, regular, 10.5, contentWidth)) {
      room(14.5);
      if (line) draw(line, MARGIN, y, { size: 10.5 });
      y -= 14.5;
    }
  };
  section('What they have owned in production', profile.evidence);
  section('Why we are presenting them', profile.why);

  // The ask.
  y -= 18;
  room(30);
  const ask = firm.email && firm.phone
    ? `To arrange an introduction, email ${firm.email} or call ${firm.phone} and quote Ref. ${profile.reference}.`
    : firm.email || firm.phone
      ? `To arrange an introduction, contact ${firm.email || firm.phone} and quote Ref. ${profile.reference}.`
      : `To arrange an introduction, contact ${firm.firmName} and quote Ref. ${profile.reference}.`;
  for (const line of wrap(ask, bold, 10.5, contentWidth)) {
    room(14.5);
    draw(line, MARGIN, y, { font: bold, size: 10.5 });
    y -= 14.5;
  }

  // A footer on every page.
  const pages = doc.getPages();
  pages.forEach((page, index) => {
    pdf.page = page;
    draw('Confidential. Please keep this profile within your hiring team.', MARGIN, MARGIN, { size: 9, color: INK_3 });
    draw(
      `${firm.firmName}  |  Ref. ${profile.reference}${pages.length > 1 ? `  |  Page ${index + 1} of ${pages.length}` : ''}`,
      RIGHT,
      MARGIN,
      { size: 9, color: INK_3, align: 'right' },
    );
  });

  return doc.save();
}

/** Makes the profile and saves it as a file. */
export async function downloadProfile(details) {
  savePdf(await profilePdf(details), fileName(`Candidate profile ${details.profile.reference}`));
}
