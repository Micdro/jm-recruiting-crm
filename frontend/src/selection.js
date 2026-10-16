import { useRef, useState } from 'react';

/**
 * Which rows of a list are ticked, for bulk actions. Only rows the filters still show count,
 * so a narrower filter never acts on rows you can't see. Shift-click ticks (or unticks) every
 * row between the last one clicked and this one.
 */
export function useSelection(rows) {
  const [ticked, setTicked] = useState(() => new Set());
  const last = useRef(null);

  const order = rows.map((row) => row.id);
  const shown = new Set(order);
  const ids = order.filter((id) => ticked.has(id));
  const all = order.length > 0 && ids.length === order.length;

  function toggle(id, range = false) {
    const on = !ticked.has(id);
    const from = order.indexOf(last.current);
    const to = order.indexOf(id);
    const span = range && from >= 0 && to >= 0 ? order.slice(Math.min(from, to), Math.max(from, to) + 1) : [id];
    last.current = id;
    setTicked((current) => {
      const next = new Set(current);
      span.forEach((rowId) => (on ? next.add(rowId) : next.delete(rowId)));
      return next;
    });
  }

  return {
    ids,
    count: ids.length,
    all,
    some: ids.length > 0 && !all,
    has: (id) => shown.has(id) && ticked.has(id),
    toggle,
    toggleAll: () => setTicked(all ? new Set() : new Set(order)),
    clear: () => setTicked(new Set()),
  };
}
