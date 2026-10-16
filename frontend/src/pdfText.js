// Shared drawing for the PDFs the CRM makes in the browser (invoices, blind profiles), with pdf-lib.
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';
import logoUrl from './assets/jm-logo.png';

export const PAGE = { width: 612, height: 792 }; // US Letter
export const MARGIN = 54;
export const RIGHT = PAGE.width - MARGIN;
export const INK = rgb(0x14 / 255, 0x24 / 255, 0x3a / 255);
export const INK_2 = rgb(0x4a / 255, 0x58 / 255, 0x68 / 255);
export const INK_3 = rgb(0x6b / 255, 0x76 / 255, 0x86 / 255);
export const BRAND = rgb(0x00 / 255, 0x40 / 255, 0x80 / 255); // Jane Michael blue
export const GOLD = rgb(0xda / 255, 0xa5 / 255, 0x20 / 255);
export const RULE = rgb(0xdd / 255, 0xe3 / 255, 0xea / 255);

const LOGO_HEIGHT = 32;

/** The logo's PNG bytes, or null if it can't be loaded (the PDF then shows the firm's name instead). */
async function logoBytes() {
  try {
    const response = await fetch(logoUrl);
    return response.ok ? await response.arrayBuffer() : null;
  } catch {
    return null;
  }
}

const LONG_DATE = new Intl.DateTimeFormat('en-US', { month: 'long', day: 'numeric', year: 'numeric', timeZone: 'UTC' });

/** "2026-11-13" as "November 13, 2026". */
export function longDate(iso) {
  if (!iso) return '';
  const [year, month, day] = iso.split('-').map(Number);
  return LONG_DATE.format(new Date(Date.UTC(year, month - 1, day)));
}

/** A name that's safe to save a file under. */
export function fileName(text) {
  return `${String(text).replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, ' ').trim()}.pdf`;
}

/**
 * A new one-page document with Helvetica, and helpers that draw on its current page. {@code page}
 * changes when {@code newPage()} adds one.
 */
export async function createPdf({ title, author }) {
  const doc = await PDFDocument.create();
  doc.setTitle(title);
  doc.setAuthor(author ?? '');
  doc.setCreator('JM CRM');
  const regular = await doc.embedFont(StandardFonts.Helvetica);
  const bold = await doc.embedFont(StandardFonts.HelveticaBold);
  const supported = new Set(regular.getCharacterSet());
  const png = await logoBytes();
  const logo = png ? await doc.embedPng(png).catch(() => null) : null;

  const pdf = { doc, regular, bold, hasLogo: Boolean(logo), page: doc.addPage([PAGE.width, PAGE.height]) };

  // Helvetica covers Western European text; anything else becomes the nearest plain letter or "?".
  pdf.clean = (value) =>
    [...String(value ?? '')]
      .map((char) => {
        if (char === '\n' || supported.has(char.codePointAt(0))) return char;
        const plain = char.normalize('NFKD').replace(/[̀-ͯ]/g, '');
        return plain && [...plain].every((part) => supported.has(part.codePointAt(0))) ? plain : '?';
      })
      .join('');

  pdf.width = (text, font, size) => font.widthOfTextAtSize(pdf.clean(text), size);

  pdf.draw = (text, x, y, { font = regular, size = 10, color = INK, align = 'left' } = {}) => {
    const value = pdf.clean(text);
    const left = align === 'right' ? x - font.widthOfTextAtSize(value, size) : x;
    pdf.page.drawText(value, { x: left, y, size, font, color });
  };

  /**
   * The top left of the first page: the firm's logo, or the firm's name in bold if the logo couldn't
   * load. Returns where the next line goes.
   */
  pdf.letterhead = (name, size = 13) => {
    const top = PAGE.height - MARGIN;
    if (logo) {
      const width = logo.width * (LOGO_HEIGHT / logo.height);
      pdf.page.drawImage(logo, { x: MARGIN, y: top + 4 - LOGO_HEIGHT, width, height: LOGO_HEIGHT });
      return top + 4 - LOGO_HEIGHT - 16;
    }
    pdf.draw(name, MARGIN, top - 10, { font: bold, size });
    return top - 10 - size - 3;
  };

  /** Breaks text into lines that fit, keeping the line breaks it already has. */
  pdf.wrap = (text, font, size, maxWidth) => {
    const lines = [];
    for (const paragraph of pdf.clean(text).split('\n')) {
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

  pdf.rule = (y, color = RULE, thickness = 0.75) =>
    pdf.page.drawLine({ start: { x: MARGIN, y }, end: { x: RIGHT, y }, thickness, color });

  /** Starts another page and returns where writing begins on it. */
  pdf.newPage = () => {
    pdf.page = doc.addPage([PAGE.width, PAGE.height]);
    return PAGE.height - MARGIN;
  };

  return pdf;
}

/** Saves PDF bytes as a download. */
export function savePdf(bytes, name) {
  const url = URL.createObjectURL(new Blob([bytes], { type: 'application/pdf' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = name;
  document.body.appendChild(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10_000);
}
