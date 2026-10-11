/**
 * The day's touches as tally marks: one mark per touch, grouped in fives,
 * with the daily target and the stretch goal marked on the line.
 */
export default function TallyMeter({ count, target, stretch, compact = false }) {
  const total = Math.max(stretch, count);
  const marks = Array.from({ length: total }, (_, index) => index + 1);

  return (
    <div
      className={`tally ${compact ? 'tally-compact' : ''}`}
      role="meter"
      aria-valuemin={0}
      aria-valuemax={stretch}
      aria-valuenow={count}
      aria-label={`${count} touches today. Target ${target}, stretch ${stretch}.`}
    >
      {marks.map((mark) => {
        const classes = ['tally-mark'];
        if (mark <= count) classes.push('tally-on');
        if (mark > target) classes.push('tally-stretch');
        if (mark % 5 === 0) classes.push('tally-group-end');
        if (mark === target) classes.push('tally-target');
        return <span key={mark} className={classes.join(' ')} />;
      })}
    </div>
  );
}
