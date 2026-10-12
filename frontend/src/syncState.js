/** A computer counts as in step if its changes were read within this long (sync runs every few seconds). */
export const FRESH_SECONDS = 60;
/** A direct connection counts as working if it last worked within this long. */
const DIRECT_SECONDS = 30;

export function connectedDirectly(peer) {
  return peer.secondsSinceDirect !== null && peer.secondsSinceDirect !== undefined && peer.secondsSinceDirect < DIRECT_SECONDS;
}

/** Whether this computer has everything the other one has shared, by either route. */
export function inStep(peer) {
  return (
    connectedDirectly(peer) ||
    (peer.secondsSinceSuccess !== null && peer.secondsSinceSuccess < FRESH_SECONDS && !peer.lastError)
  );
}
