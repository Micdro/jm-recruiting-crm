// Shared drawing for the PDFs the CRM makes in the browser (invoices, blind profiles), with pdf-lib.
import { PDFDocument, StandardFonts, rgb } from 'pdf-lib';

export const PAGE = { width: 612, height: 792 }; // US Letter
export const MARGIN = 54;
export const RIGHT = PAGE.width - MARGIN;
export const INK = rgb(0x17 / 255, 0x24 / 255, 0x2d / 255);
export const INK_2 = rgb(0x48 / 255, 0x57 / 255, 0x5f / 255);
export const INK_3 = rgb(0x75 / 255, 0x84 / 255, 0x8b / 255);
export const GREEN = rgb(0x1d / 255, 0x6b / 255, 0x57 / 255);
export const RULE = rgb(0xdc / 255, 0xe2 / 255, 0xe0 / 255);

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

  const pdf = { doc, regular, bold, page: doc.addPage([PAGE.width, PAGE.height]) };

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
