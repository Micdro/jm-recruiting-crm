// Matching and ranking for the quick search (Ctrl+K). Kept apart from the component so it stays simple to check.

/** Lower case without accents, so "jose" finds "José". */
export function fold(text) {
  return String(text ?? '')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase();
}

const WORD_BREAK = /[\s\-.,@()/&']+/;

/**
 * Prepares records for searching: each gets its title and everything else it can be found by,
 * folded once rather than on every keystroke.
 */
export function buildIndex(records, { title, fields }) {
  return (records ?? []).map((record) => {
    const name = title(record) ?? '';
    const folded = fold(name);
    return {
      record,
      folded,
      words: folded.split(WORD_BREAK).filter(Boolean),
      everything: [folded, ...fields(record).map(fold)].join('\n'),
    };
  });
}

/**
 * How well an indexed record matches, from 0 (no match) to 100. Every word typed has to appear
 * somewhere in the record; matches on the name rank above matches elsewhere, and the start of the
 * name or of a word in it ranks highest.
 */
export function score(entry, query) {
  const typed = fold(query).trim();
  const words = typed.split(/\s+/).filter(Boolean);
  if (words.length === 0 || !words.every((word) => entry.everything.includes(word))) return 0;
  if (entry.folded === typed) return 100;
  if (entry.folded.startsWith(typed)) return 90;
  const inName = words.every((word) => entry.folded.includes(word));
  if (inName && words.every((word) => entry.words.some((part) => part.startsWith(word)))) return 70;
  if (inName) return 50;
  return 20;
}

/** The best matches, best first, then alphabetical. */
export function search(index, query, limit) {
  const found = [];
  for (const entry of index) {
    const value = score(entry, query);
    if (value > 0) found.push({ entry, value });
  }
  found.sort((first, second) => second.value - first.value || first.entry.folded.localeCompare(second.entry.folded));
  return found.slice(0, limit).map(({ entry }) => entry.record);
}

/** The shortcut as this computer shows it. */
export function shortcutLabel() {
  const platform = typeof navigator === 'undefined' ? '' : (navigator.userAgentData?.platform ?? navigator.platform ?? '');
  return /mac|iphone|ipad/i.test(platform) ? '⌘K' : 'Ctrl K';
}
