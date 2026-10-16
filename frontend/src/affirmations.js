/**
 * The affirmation shown on Today. Everyone sees the same one on the same day, and it changes at
 * midnight. The list goes round in order, so nothing repeats for two months.
 */
export const AFFIRMATIONS = [
  'Every message you send today is a door someone might open.',
  'A no today is information, not a verdict.',
  'Consistency beats intensity. Thirty good touches is a great day.',
  'You make careers move. That matters.',
  'The right candidate is out there, and you’re the one who will find them.',
  'Silence isn’t rejection. Plenty of people answer on the third or fourth touch.',
  'You know this market better than most people messaging these buyers.',
  'Small firm, sharp focus. That’s the advantage.',
  'Today’s follow-up is tomorrow’s placement.',
  'Be curious about every person you message. It shows.',
  'You don’t need everyone to reply. You need the right ones.',
  'Good recruiting is good listening. Lead with questions.',
  'Every placement started as one cold message.',
  'You’re building something real, one conversation at a time.',
  'Progress counts even when it’s quiet.',
  'Your work changes what a team can build.',
  'Treat every candidate like your best referral source. They often are.',
  'Momentum is made, not found. Start with the first touch.',
  'A clear, honest message beats a clever one.',
  'You’ve solved hard searches before. You’ll solve this one.',
  'Rejection is part of the job, not a judgment of you.',
  'Bring the energy you’d want from someone reaching out to you.',
  'One great conversation can change the whole quarter.',
  'Focus on the next action. That’s all today asks.',
  'The people who reply today will remember how you treated them.',
  'You’re the bridge between a great engineer and the team that needs them.',
  'Do the follow-ups. That’s where the deals are.',
  'Steady beats lucky.',
  'Reputations are built in small moments: answering fast and being straight with people.',
  'Good work compounds. Today’s effort pays off months from now.',
  'Celebrate the replies. Learn from the silence.',
  'You belong in these conversations. Show up like it.',
  'Be the recruiter people are glad they answered.',
  'Hard days still move the pipeline forward.',
  'Clients hire you for your judgment. Trust it.',
  'Every candidate you help is a story you get to be part of.',
  'Keep it simple: right person, right message, right time.',
  'Nobody builds a book of business in a day. Keep stacking days.',
  'What you do today shows up in next month’s numbers.',
  'You’re good at this, and you’re getting better.',
  'Ask one more question than you think you need to.',
  '“Not right now” means “check back later.” Put the date in.',
  'Your team has your back. Lean on them.',
  'Energy follows action. Send the first message.',
  'Be generous with what you know. It builds trust faster than a pitch.',
  'The best time to follow up is the day you said you would.',
  'You’re doing work most people won’t. That’s the edge.',
  'Every touch is practice, and practice makes the next one better.',
  'Make one person’s day better today, candidate or client.',
  'Placements are a team effort. Thank someone today.',
  'You can’t control replies. You can control effort, and effort wins.',
  'Be patient with people and relentless with the process.',
  'Today is a fresh queue.',
  'Trust the process you built.',
  'The hardest part is starting, and you’re already here.',
  'Clarity is kindness. Say what you need and what happens next.',
  'Good recruiters remember people. Great ones remember what people care about.',
  'Every great hire you make strengthens a team that people rely on.',
  'You get to help people do the best work of their careers.',
  'Finish strong. The last touch of the day counts as much as the first.',
];

const DAY_MS = 24 * 60 * 60 * 1000;

/** The affirmation for a day, given as "2026-10-15". */
export function affirmationFor(isoDay) {
  const [year, month, day] = String(isoDay ?? '').split('-').map(Number);
  const dayNumber = Math.floor(Date.UTC(year, month - 1, day) / DAY_MS);
  if (!Number.isFinite(dayNumber)) return AFFIRMATIONS[0];
  const count = AFFIRMATIONS.length;
  return AFFIRMATIONS[((dayNumber % count) + count) % count];
}
