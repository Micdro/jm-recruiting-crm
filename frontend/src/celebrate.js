/**
 * A short burst of confetti over the page, for a placement. Nothing moves for people who ask for
 * reduced motion, and it never blocks a click: the canvas ignores the pointer and removes itself.
 */
const COLORS = ['#daa520', '#f0d58c', '#004080', '#6fc3a6', '#1d6b57', '#ffffff'];
const PIECES = 140;
const DURATION_MS = 2600;

export function celebrate() {
  if (typeof window === 'undefined' || !document.body) return;
  if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;

  const canvas = document.createElement('canvas');
  canvas.className = 'confetti';
  canvas.setAttribute('aria-hidden', 'true');
  const ratio = window.devicePixelRatio || 1;
  const width = window.innerWidth;
  const height = window.innerHeight;
  canvas.width = width * ratio;
  canvas.height = height * ratio;
  document.body.appendChild(canvas);
  const context = canvas.getContext('2d');
  if (!context) {
    canvas.remove();
    return;
  }
  context.scale(ratio, ratio);

  // Two bursts from the lower corners, toward the middle of the page.
  const pieces = Array.from({ length: PIECES }, (_, index) => {
    const left = index % 2 === 0;
    const angle = (left ? -60 : -120) + (Math.random() - 0.5) * 40;
    const speed = 9 + Math.random() * 7;
    return {
      x: left ? width * 0.1 : width * 0.9,
      y: height * 0.95,
      vx: Math.cos((angle * Math.PI) / 180) * speed,
      vy: Math.sin((angle * Math.PI) / 180) * speed,
      size: 6 + Math.random() * 6,
      spin: Math.random() * Math.PI,
      turn: (Math.random() - 0.5) * 0.3,
      color: COLORS[index % COLORS.length],
    };
  });

  const start = performance.now();
  function frame(now) {
    const elapsed = now - start;
    context.clearRect(0, 0, width, height);
    context.globalAlpha = Math.max(0, 1 - Math.max(0, elapsed - DURATION_MS * 0.6) / (DURATION_MS * 0.4));
    for (const piece of pieces) {
      piece.vy += 0.25;
      piece.vx *= 0.99;
      piece.x += piece.vx;
      piece.y += piece.vy;
      piece.spin += piece.turn;
      context.save();
      context.translate(piece.x, piece.y);
      context.rotate(piece.spin);
      context.fillStyle = piece.color;
      context.fillRect(-piece.size / 2, -piece.size / 4, piece.size, piece.size / 2);
      context.restore();
    }
    if (elapsed < DURATION_MS) requestAnimationFrame(frame);
    else canvas.remove();
  }
  requestAnimationFrame(frame);
}
