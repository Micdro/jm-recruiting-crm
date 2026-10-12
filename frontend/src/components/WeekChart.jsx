import { useEffect, useRef, useState } from 'react';
import { formatDate } from '../format.js';

const HEIGHT = 220;
const MARGIN = { top: 14, right: 8, bottom: 40, left: 40 };
const MAX_BAR = 24;
const GAP = 2;
const RADIUS = 4;
const TIP_WIDTH = 180;

/** The width of an element, kept up to date as it resizes. */
function useWidth() {
  const ref = useRef(null);
  const [width, setWidth] = useState(0);
  useEffect(() => {
    const element = ref.current;
    if (!element) return undefined;
    const observer = new ResizeObserver(([entry]) => setWidth(Math.floor(entry.contentRect.width)));
    observer.observe(element);
    return () => observer.disconnect();
  }, []);
  return [ref, width];
}

/** Round steps (1, 2, 5, 10, 20, 50...) from zero to just above max. */
function niceTicks(max, count = 4) {
  const top = Math.max(max, count);
  const raw = top / count;
  const power = 10 ** Math.floor(Math.log10(raw));
  const step = Math.max(1, [1, 2, 5, 10].map((multiple) => multiple * power).find((value) => value >= raw));
  const ticks = [];
  for (let tick = 0; tick < top + step; tick += step) ticks.push(tick);
  return ticks;
}

/** A bar with rounded top corners and a square foot on the baseline. */
function barPath(x, y, width, height, rounded) {
  const r = rounded ? Math.min(RADIUS, height, width / 2) : 0;
  const bottom = y + height;
  return `M${x},${bottom}V${y + r}Q${x},${y} ${x + r},${y}H${x + width - r}Q${x + width},${y} ${x + width},${y + r}V${bottom}Z`;
}

/**
 * Columns for each week, stacked when there's more than one series. Hovering a week shows its
 * numbers. The last week is the current one, so far.
 *
 * @param series [{ key, label, color }], bottom of the stack first
 * @param target an optional line across the chart, such as the weekly touch target
 * @param describe extra lines for a week's tooltip
 */
export default function WeekChart({ weeks, series, target, label, describe }) {
  const [ref, width] = useWidth();
  const [hover, setHover] = useState(null);

  const totals = weeks.map((week) => series.reduce((sum, item) => sum + week[item.key], 0));
  const ticks = niceTicks(Math.max(...totals, target ? target * 1.08 : 0));
  const top = ticks[ticks.length - 1];
  const plotWidth = Math.max(0, width - MARGIN.left - MARGIN.right);
  const plotHeight = HEIGHT - MARGIN.top - MARGIN.bottom;
  const band = plotWidth / weeks.length;
  const barWidth = Math.max(4, Math.min(MAX_BAR, band * 0.6));
  const y = (value) => MARGIN.top + plotHeight - (value / top) * plotHeight;
  const center = (index) => MARGIN.left + band * index + band / 2;
  const everyOther = band < 46;
  const last = weeks.length - 1;

  const hovered = hover === null ? null : weeks[hover];
  // Beside the column, so the bars being described stay in view.
  const tipSide = hover !== null && center(hover) + band / 2 + TIP_WIDTH + 8 > width ? 'left' : 'right';
  const tipStyle =
    hover === null
      ? null
      : tipSide === 'right'
        ? { left: center(hover) + band / 2 + 4 }
        : { left: Math.max(0, center(hover) - band / 2 - 4 - TIP_WIDTH) };

  return (
    <div className="week-chart" ref={ref}>
      {width > 0 && (
        <svg width={width} height={HEIGHT} role="img" aria-label={label} onMouseLeave={() => setHover(null)}>
          {ticks.map((tick) => (
            <g key={tick}>
              <line className="chart-grid" x1={MARGIN.left} x2={width - MARGIN.right} y1={y(tick)} y2={y(tick)} />
              <text className="chart-tick" x={MARGIN.left - 8} y={y(tick)} dy="0.32em" textAnchor="end">
                {tick}
              </text>
            </g>
          ))}

          {hover !== null && (
            <rect className="chart-hover" x={center(hover) - band / 2} y={MARGIN.top} width={band} height={plotHeight} />
          )}

          {weeks.map((week, index) => {
            const x = center(index) - barWidth / 2;
            const visible = series.filter((item) => week[item.key] > 0);
            let sum = 0;
            return (
              <g key={week.start}>
                {visible.map((item, position) => {
                  const bottom = y(sum) - (position > 0 ? GAP : 0);
                  sum += week[item.key];
                  const height = bottom - y(sum);
                  if (height <= 0) return null;
                  return (
                    <path
                      key={item.key}
                      d={barPath(x, y(sum), barWidth, height, position === visible.length - 1)}
                      style={{ fill: item.color }}
                    />
                  );
                })}
              </g>
            );
          })}

          {target ? (
            <line className="chart-target" x1={MARGIN.left} x2={width - MARGIN.right} y1={y(target)} y2={y(target)} />
          ) : null}

          <line className="chart-axis" x1={MARGIN.left} x2={width - MARGIN.right} y1={y(0)} y2={y(0)} />
          {weeks.map((week, index) =>
            everyOther && (last - index) % 2 === 1 ? null : (
              <text key={week.start} className="chart-tick" x={center(index)} y={HEIGHT - MARGIN.bottom + 18} textAnchor="middle">
                {formatDate(week.start)}
                {index === last && (
                  <tspan x={center(index)} dy="1.2em">
                    so far
                  </tspan>
                )}
              </text>
            ),
          )}

          {weeks.map((week, index) => (
            <rect
              key={week.start}
              className="chart-hit"
              x={center(index) - band / 2}
              y={MARGIN.top}
              width={band}
              height={plotHeight + 24}
              onMouseEnter={() => setHover(index)}
            />
          ))}
        </svg>
      )}

      {hovered && (
        <div className="chart-tip" style={{ ...tipStyle, width: TIP_WIDTH }} role="presentation">
          <p className="chart-tip-title">
            Week of {formatDate(hovered.start)}
            {hover === last ? ', so far' : ''}
          </p>
          {series.length > 1 &&
            series.map((item) => (
              <p key={item.key} className="chart-tip-row">
                <span className="swatch" style={{ background: item.color }} />
                <span>{item.label}</span>
                <span className="chart-tip-value">{hovered[item.key]}</span>
              </p>
            ))}
          {describe(hovered).map((line) => (
            <p key={line} className="chart-tip-row chart-tip-total">
              {line}
            </p>
          ))}
        </div>
      )}
    </div>
  );
}

/** The series' colors, and the target line if the chart has one. */
export function Legend({ series, target }) {
  return (
    <ul className="legend">
      {series.map((item) => (
        <li key={item.key}>
          <span className="swatch" style={{ background: item.color }} />
          {item.label}
        </li>
      ))}
      {target ? (
        <li>
          <span className="swatch swatch-line" />
          Target {target}
        </li>
      ) : null}
    </ul>
  );
}
